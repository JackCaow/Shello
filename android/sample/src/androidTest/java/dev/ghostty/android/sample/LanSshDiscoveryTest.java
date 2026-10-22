package dev.ghostty.android.sample;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class LanSshDiscoveryTest {
    @Test public void subnetBoundsExcludeSelfNetworkBroadcastAndCapLargeNetworks() throws Exception {
        Inet4Address ip=(Inet4Address)InetAddress.getByName("192.168.15.223");
        List<String> hosts=LanSshDiscovery.targets(ip,24);
        assertEquals(253,hosts.size());assertTrue(hosts.contains("192.168.15.111"));assertTrue(hosts.contains("192.168.15.191"));
        assertFalse(hosts.contains("192.168.15.223"));assertFalse(hosts.contains("192.168.15.0"));assertFalse(hosts.contains("192.168.15.255"));
        assertEquals(hosts,LanSshDiscovery.targets(ip,16));
        assertEquals(Collections.singletonList("192.168.15.222"),LanSshDiscovery.targets(ip,31));
        assertTrue(LanSshDiscovery.targets(ip,32).isEmpty());
        assertEquals(Collections.singletonList("192.168.15.222"),LanSshDiscovery.targets((Inet4Address)InetAddress.getByName("192.168.15.221"),30));
    }
    @Test public void bannerRequiresSshIdentificationNotJustOpenTcpPort() throws Exception {
        assertTrue(probe("SSH-2.0-OpenSSH_9.9\r\n"));
        assertTrue(probe("Notice\r\nSSH-1.99-server\r\n"));
        assertFalse(probe("HTTP/1.1 200 OK\r\n"));
        assertFalse(probe("SSH-1.5-old\r\n"));assertFalse(probe("SSH-2.0-\r\n"));
        assertFalse(probe(String.join("",Collections.nCopies(300,"a"))+"\nSSH-2.0-server\n"));
    }
    private boolean probe(String banner) throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            ExecutorService executor=Executors.newSingleThreadExecutor();
            Future<?> sent=executor.submit(()->{
                try(Socket socket=server.accept()){socket.getOutputStream().write(banner.getBytes(StandardCharsets.US_ASCII));}
                catch(Exception e){throw new RuntimeException(e);}
            });
            try(Socket client=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort())) {
                boolean result=LanSshDiscovery.hasSshBanner(client,500);sent.get(2,TimeUnit.SECONDS);return result;
            }finally{executor.shutdownNow();}
        }
    }
    @Test public void silentListenerHasBoundedTimeout() throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
            Socket client=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());Socket peer=server.accept()) {
            long start=System.nanoTime();
            try{assertFalse(LanSshDiscovery.hasSshBanner(client,120));}catch(SocketTimeoutException expected){}
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000);
        }
    }
    @Test public void ptrResponseValidatesQuestionIdBoundsAndCompressionCycles() throws Exception {
        byte[] query=PtrDns.query("192.168.15.1",42);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        byte[] header=query.clone();header[2]=(byte)0x81;header[3]=(byte)0x80;header[7]=1;out.write(header);
        out.writeShort(0xc00c);out.writeShort(12);out.writeShort(1);out.writeInt(60);
        out.writeShort(10);out.writeByte(4);out.writeBytes("Kwrt");out.writeByte(3);out.writeBytes("lan");out.writeByte(0);
        byte[] reply=bytes.toByteArray();
        assertEquals("Kwrt.lan",PtrDns.answer(reply,reply.length,42,query));
        assertNull(PtrDns.answer(reply,reply.length,43,query));
        assertNull(PtrDns.answer(reply,reply.length,42,PtrDns.query("192.168.15.2",42)));
        byte[] truncated=reply.clone();truncated[2]|=2;assertNull(PtrDns.answer(truncated,truncated.length,42,query));
        try{PtrDns.answer(reply,reply.length-1,42,query);fail("Truncated record accepted");}catch(IOException expected){}
        byte[] cycle=reply.clone();int start=query.length+12;cycle[start]=(byte)(0xc0|(start>>8));cycle[start+1]=(byte)start;
        try{PtrDns.answer(cycle,cycle.length,42,query);fail("Compression cycle accepted");}catch(IOException expected){}
        // A compressed PTR name is allowed, not just uncompressed RDATA.
        byte[] compressed=reply.clone();compressed[start]=(byte)0xc0;compressed[start+1]=12;
        assertEquals("1.15.168.192.in-addr.arpa",PtrDns.answer(compressed,compressed.length,42,query));
    }
    @Test public void physicalWifiFindsExpectedSshHostsAndCancellationStopsCallbacks() throws Exception {
        String expected=InstrumentationRegistry.getArguments().getString("expectedSshHosts");
        org.junit.Assume.assumeTrue("Pass expectedSshHosts for an explicit physical LAN check",expected!=null);
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        LanSshDiscovery.Scope scope=LanSshDiscovery.wifiScope((ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE));
        assertNotNull("Physical Wi-Fi IPv4 network required",scope);
        Handler main=new Handler(Looper.getMainLooper());CountDownLatch done=new CountDownLatch(1);
        AtomicReference<List<LanSshDiscovery.Host>> result=new AtomicReference<>();
        AtomicReference<LanSshDiscovery> scanner=new AtomicReference<>();AtomicInteger callbacks=new AtomicInteger();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            LanSshDiscovery scan=new LanSshDiscovery(context,scope,(hosts,scanning,status)->{
                callbacks.incrementAndGet();if(!scanning){result.set(hosts);done.countDown();}
            });scanner.set(scan);scan.start();
        });
        try{
            assertTrue("Scan must finish within its bound",done.await(25,TimeUnit.SECONDS));
            Set<String> actual=new HashSet<>();for(LanSshDiscovery.Host h:result.get())actual.add(h.address);
            for(String address:expected.split(","))assertTrue("Missing expected SSH host "+address,actual.contains(address));
            String ptrHost=InstrumentationRegistry.getArguments().getString("expectedPtrHost");
            String ptrName=InstrumentationRegistry.getArguments().getString("expectedPtrName");
            if(ptrHost!=null){
                LanSshDiscovery.Host match=null;for(LanSshDiscovery.Host h:result.get())if(h.address.equals(ptrHost))match=h;
                assertNotNull("PTR fixture must be discovered",match);assertEquals(ptrName,match.name);
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->scanner.get().close());
            int previous=callbacks.get();Thread.sleep(1000);assertEquals(previous,callbacks.get());
            // Cancel while work is in flight, not only after completion.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                LanSshDiscovery scan=new LanSshDiscovery(context,scope,(h,s,m)->callbacks.incrementAndGet());scanner.set(scan);scan.start();scan.close();
            });
            previous=callbacks.get();Thread.sleep(1000);assertEquals(previous,callbacks.get());
        }finally{main.post(()->scanner.get().close());}
    }
}
