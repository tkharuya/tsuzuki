package jp.haruyalab.novelshelf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real IPv4-loopback sockets with synthetic OAuth codes only; never opens a browser or provider. */
public final class ChatGptLoopbackTest {
    private static final String HOST="urn:uuid:09341b15-2b0b-4eb3-8b4d-eb9f157f8042";
    private static final String CLIENT="fixture_loopback_issued_client";
    private static final String CODE="fixture_private_code_<script>alert('fixture')</script>&token=PRIVATE";
    private static final String PRIVATE="fixture_private_provider_error_do_not_reflect";
    private static int checks;
    private interface Operation { void run() throws Exception; }
    private static void yes(boolean value,String label) { checks++; if(!value) throw new AssertionError(label); }
    private static void eq(Object actual,Object expected) { checks++; if(!expected.equals(actual)) throw new AssertionError("expected "+expected+", got "+actual); }
    private static void invalid(Operation run,String label) throws Exception {
        try { run.run(); throw new AssertionError("accepted "+label); }
        catch(IOException expected) { checks++; }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String encoded(String value) throws Exception { return URLEncoder.encode(value,"UTF-8"); }
    private static String request(String target,int port) { return "GET "+target+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\n\r\n"; }
    private static String parser(String request,int port) throws Exception { return ChatGptLoopback.target(new ByteArrayInputStream(bytes(request)),port); }
    private static String repeat(char value,int count) { char[] result=new char[count]; java.util.Arrays.fill(result,value); return new String(result); }
    private static ServerSocket server(ChatGptLoopback listener) throws Exception {
        Field field=ChatGptLoopback.class.getDeclaredField("server"); field.setAccessible(true); return (ServerSocket)field.get(listener);
    }
    private static Socket current(ChatGptLoopback listener) throws Exception {
        Field field=ChatGptLoopback.class.getDeclaredField("current"); field.setAccessible(true); return (Socket)field.get(listener);
    }
    private static final class Harness implements AutoCloseable {
        final ChatGptLoopback listener=new ChatGptLoopback();
        final int port=listener.port();
        final ChatGptOAuth.Attempt attempt;
        final AtomicBoolean cancelled=new AtomicBoolean();
        final AtomicReference<ChatGptOAuth.Callback> callback=new AtomicReference<>();
        final AtomicReference<Throwable> error=new AtomicReference<>();
        final CountDownLatch entered=new CountDownLatch(1),done=new CountDownLatch(1);
        final Thread worker;
        Harness() throws Exception { this(false); }
        Harness(boolean returning) throws Exception {
            attempt=ChatGptOAuth.begin(HOST,returning ? Json.obj("issuer",ChatGptOAuth.ISSUER,"subject","fixture_subject","client_id",CLIENT,"ext_agent_host_id",HOST) : null,port);
            worker=new Thread(() -> {
                entered.countDown();
                try { callback.set(listener.await(attempt,cancelled::get)); }
                catch(Throwable value) { error.set(value); }
                finally { done.countDown(); }
            },"loopback-test");
            worker.setDaemon(true); worker.start();
            if(!entered.await(1,TimeUnit.SECONDS)) throw new AssertionError("listener thread not started");
        }
        String good() throws Exception { return "/auth/callback?code="+encoded(CODE)+"&state="+attempt.state+"&client_id="+CLIENT; }
        Response send(String data) throws Exception { return send(bytes(data)); }
        Response send(byte[] data) throws Exception {
            try(Socket socket=connect(port)) {
                socket.getOutputStream().write(data); socket.getOutputStream().flush(); socket.shutdownOutput();
                return new Response(read(socket.getInputStream()));
            }
        }
        void completed() throws Exception {
            yes(done.await(2,TimeUnit.SECONDS),"callback worker completed");
            if(error.get()!=null) throw new AssertionError("unexpected listener error",error.get());
            yes(callback.get()!=null,"validated callback returned");
            eq(callback.get().code,CODE); eq(callback.get().clientId,CLIENT);
            yes(server(listener).isClosed(),"success closes listening socket");
            closedPort(port);
        }
        void expectCancelled() throws Exception {
            yes(done.await(1500,TimeUnit.MILLISECONDS),"cancel exits bounded wait");
            yes(error.get() instanceof ChatGptOAuth.AuthException,"cancellation classified as auth cancellation");
            eq(((ChatGptOAuth.AuthException)error.get()).code,"cancelled");
            yes(callback.get()==null,"cancel does not return credentials");
            yes(server(listener).isClosed(),"cancel closes listener"); closedPort(port);
        }
        public void close() {
            cancelled.set(true); listener.close();
            try { worker.join(1500); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    private static Socket connect(int port) throws Exception {
        Socket socket=new Socket(); socket.connect(new InetSocketAddress("127.0.0.1",port),1000); socket.setSoTimeout(5000); return socket;
    }
    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] buffer=new byte[1024];
        while(true) { int count=input.read(buffer); if(count<0) break; if(bytes.size()+count>65536) throw new IOException("fixture response bound"); bytes.write(buffer,0,count); }
        return bytes.toByteArray();
    }
    private static void closedPort(int port) throws Exception {
        invalid(() -> { try(Socket ignored=connect(port)) { } },"completed listener still accepts a connection");
    }
    private static final class Response {
        final int status; final String text,body; final byte[] bodyBytes;
        final Map<String,String> headers=new LinkedHashMap<>();
        Response(byte[] response) throws Exception {
            text=new String(response,StandardCharsets.UTF_8); int split=text.indexOf("\r\n\r\n");
            if(split<0) throw new AssertionError("missing HTTP response header");
            String[] lines=text.substring(0,split).split("\r\n"); status=Integer.parseInt(lines[0].split(" ")[1]);
            for(int i=1;i<lines.length;i++) { int colon=lines[i].indexOf(':'); headers.put(lines[i].substring(0,colon).toLowerCase(Locale.ROOT),lines[i].substring(colon+1).trim()); }
            body=text.substring(split+4); bodyBytes=bytes(body);
            eq(Integer.parseInt(headers.get("content-length")),bodyBytes.length);
        }
        void secure(String state) {
            eq(headers.get("content-type"),"text/html; charset=utf-8"); eq(headers.get("connection"),"close");
            eq(headers.get("cache-control"),"no-store"); eq(headers.get("pragma"),"no-cache");
            eq(headers.get("referrer-policy"),"no-referrer"); eq(headers.get("x-content-type-options"),"nosniff");
            yes(headers.get("content-security-policy").contains("default-src 'none'") && headers.get("content-security-policy").contains("frame-ancestors 'none'"),"inert non-framable response");
            yes(body.contains("tsuzuki://chatgpt/return") && body.contains("つづき"),"explicit app return link");
            yes(!text.contains(CODE) && !text.contains(CLIENT) && !text.contains(PRIVATE) && !text.contains(state)
                    && !text.contains("<script>") && !text.contains("fixture_private_code"),"HTML reflects no callback secrets or markup");
        }
    }
    public static void main(String[] args) throws Exception {
        parserConstraints(); bindingAndCallback(); badRequestsDoNotConsume(); returningAccount(); authorizationDenied(); resetAndPartialConnections(); cancellations();
        System.out.println("ChatGptLoopback production checks passed: "+checks+" (real 127.0.0.1 TCP, dynamic ports, synthetic callbacks only)");
    }
    private static void parserConstraints() throws Exception {
        int port=41001; String path="/auth/callback?code=fixture&state=fixture_state&client_id="+CLIENT;
        eq(parser(request(path,port),port),path);
        eq(parser("GET "+path+" HTTP/1.1\r\nhOsT:\t127.0.0.1:"+port+" \r\nAccept: text/html\r\nContent-Length: 0\r\n\r\n",port),path);
        for(String method:new String[]{"POST","HEAD","PUT","OPTIONS","CONNECT","get"})
            invalid(() -> parser(request(path,port).replace("GET ",method+" "),port),"unsupported method "+method);
        for(String protocol:new String[]{"HTTP/1.0","HTTP/2","HTTP/2.0","https/1.1"})
            invalid(() -> parser(request(path,port).replace("HTTP/1.1",protocol),port),"unsupported HTTP version");
        for(String host:new String[]{"localhost:"+port,"[::1]:"+port,"127.0.0.1","127.0.0.1:"+(port+1),"127.0.0.1:"+port+"@example.invalid","example.invalid","127.0.0.2:"+port,"127.0.0.1.:"+port})
            invalid(() -> parser("GET "+path+" HTTP/1.1\r\nHost: "+host+"\r\n\r\n",port),"wrong callback host");
        invalid(() -> parser("GET "+path+" HTTP/1.1\r\nAccept: text/html\r\n\r\n",port),"missing host");
        String[] forbidden={"Host: 127.0.0.1:"+port,"host: 127.0.0.1:"+port,"Content-Length: 1","Content-Length: -1","Content-Length: 00",
                "Content-Length: 0, 0","Transfer-Encoding: chunked","transfer-encoding: identity","Content-Length : 1","Content\tLength: 1",
                " X-Folded: value","X-Bad Name: value","X-Foo: value\u0001","X-Foo: value\u007f","X-Foo: value\rBroken: value","No-Colon"};
        for(String header:forbidden) invalid(() -> parser("GET "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\n"+header+"\r\n\r\n",port),"forbidden header or body framing");
        invalid(() -> parser("\r\n\r\n",port),"empty request line");
        invalid(() -> parser("GET  "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\n\r\n",port),"ambiguous request-line spacing");
        invalid(() -> parser(request(path,port).replace("code=fixture","code=\u0000"),port),"NUL input");
        invalid(() -> parser(request(path,port).replace("code=fixture","code=日"),port),"non-ASCII raw request");
        invalid(() -> parser("GET / HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\n",port),"incomplete header");
        invalid(() -> parser(request("/"+repeat('x',16385),port),port),"oversized request target");
        invalid(() -> parser("GET / HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nX-Huge: "+repeat('x',32768)+"\r\n\r\n",port),"oversized headers");
    }
    private static void bindingAndCallback() throws Exception {
        try(Harness h=new Harness()) {
            yes(h.port>0 && h.port<=65535,"kernel allocated callback port");
            eq(server(h.listener).getInetAddress(),InetAddress.getByName("127.0.0.1"));
            yes(!server(h.listener).getInetAddress().isAnyLocalAddress(),"listener never binds wildcard");
            eq(h.attempt.redirectUri,"http://127.0.0.1:"+h.port+"/auth/callback");
            Response r=h.send(request(h.good(),h.port)); eq(r.status,200); r.secure(h.attempt.state); h.completed();
        }
    }
    private static void badRequestsDoNotConsume() throws Exception {
        try(Harness h=new Harness()) {
            String good=h.good();
            String[] targets={"/favicon.ico","/auth/callback","/auth/%63allback?state="+h.attempt.state,
                    "http://127.0.0.1:"+h.port+good,"//127.0.0.1:"+h.port+good};
            for(String target:targets) { Response r=h.send(request(target,h.port)); eq(r.status,404); r.secure(h.attempt.state); }
            for(String target:new String[]{good.replace(h.attempt.state,"wrong_state"),good+"&state=duplicate",good+"&code=duplicate",good+"&client_id=duplicate",good+"&unknown=one&unknown=two"}) {
                Response r=h.send(request(target,h.port)); eq(r.status,400); r.secure(h.attempt.state);
                yes(h.done.getCount()==1 && h.callback.get()==null,"invalid callback does not consume pending attempt");
            }
            String[] malformed={"\r\n\r\n",request(good,h.port).replace("GET ","POST "),request(good,h.port).replace("127.0.0.1:"+h.port,"localhost:"+h.port),
                    "GET "+good+" HTTP/1.1\r\nHost: 127.0.0.1:"+h.port+"\r\nContent-Length : 1\r\n\r\nx",
                    "GET "+good+" HTTP/1.1\r\nHost: 127.0.0.1:"+h.port+"\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"};
            for(String data:malformed) { Response r=h.send(data); eq(r.status,400); r.secure(h.attempt.state); yes(h.done.getCount()==1,"bad HTTP request leaves listener active"); }
            Response accepted=h.send(request(good,h.port)); eq(accepted.status,200); accepted.secure(h.attempt.state); h.completed();
        }
    }
    private static void returningAccount() throws Exception {
        try(Harness h=new Harness(true)) {
            String target="/auth/callback?state="+h.attempt.state+"&code="+encoded(CODE);
            Response r=h.send(request(target,h.port)); eq(r.status,200); r.secure(h.attempt.state); h.completed();
        }
    }
    private static void authorizationDenied() throws Exception {
        try(Harness h=new Harness()) {
            String target="/auth/callback?state="+h.attempt.state+"&error=access_denied&error_description="+PRIVATE;
            Response r=h.send(request(target,h.port)); eq(r.status,400); r.secure(h.attempt.state);
            yes(h.done.await(1,TimeUnit.SECONDS),"declined authorization completes listener");
            yes(h.error.get() instanceof ChatGptOAuth.AuthException,"denial classified");
            eq(((ChatGptOAuth.AuthException)h.error.get()).code,"access_denied");
            yes(h.callback.get()==null && server(h.listener).isClosed(),"decline closes pending attempt"); closedPort(h.port);
        }
    }
    private static void resetAndPartialConnections() throws Exception {
        try(Harness h=new Harness()) {
            try(Socket reset=connect(h.port)) {
                reset.setSoLinger(true,0); reset.getOutputStream().write(bytes("GET /unrelated")); reset.getOutputStream().flush();
            }
            Response incomplete=h.send("GET /incomplete HTTP/1.1\r\n"); eq(incomplete.status,400); incomplete.secure(h.attempt.state);
            Response valid=h.send(request(h.good(),h.port)); eq(valid.status,200); h.completed();
        }
    }
    private static void waitForAccept(Harness h) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        while(System.nanoTime()<deadline) {
            for(StackTraceElement frame:h.worker.getStackTrace()) if(frame.getClassName().equals("java.net.ServerSocket") && frame.getMethodName().contains("accept")) { checks++; return; }
            Thread.sleep(5);
        }
        throw new AssertionError("listener not blocked in accept");
    }
    private static void waitForRead(Harness h) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        while(System.nanoTime()<deadline) { if(current(h.listener)!=null) { checks++; return; } Thread.sleep(5); }
        throw new AssertionError("listener did not accept partial connection");
    }
    private static void cancellations() throws Exception {
        try(Harness h=new Harness()) { waitForAccept(h); h.cancelled.set(true); h.expectCancelled(); }
        try(Harness h=new Harness()) { waitForAccept(h); h.listener.close(); h.expectCancelled(); }
        try(Harness h=new Harness(); Socket partial=connect(h.port)) {
            partial.getOutputStream().write(bytes("GET /auth/callback?")); partial.getOutputStream().flush(); waitForRead(h);
            h.cancelled.set(true); h.listener.close(); h.expectCancelled();
        }
    }
}
