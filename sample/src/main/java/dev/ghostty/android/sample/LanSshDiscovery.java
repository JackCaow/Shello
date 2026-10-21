package dev.ghostty.android.sample;

import android.content.Context;
import android.net.*;
import android.net.nsd.*;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Foreground-only sample discovery. No authentication, credentials or terminal traffic. */
final class LanSshDiscovery implements AutoCloseable {
    static final int PORT=22, WORKERS=16;
    interface Listener { void update(List<Host> hosts, boolean scanning, String status); }
    static final class Host {
        final String address, name;
        Host(String address,String name){this.address=address;this.name=name;}
    }
    static final class Scope {
        final Network network;
        final List<String> addresses;
        final String label;
        final List<InetAddress> dnsServers;
        Scope(Network network,Inet4Address local,int prefix,List<InetAddress> dnsServers) {
            this.dnsServers=new ArrayList<>(dnsServers);
            this.network=network;addresses=targets(local,prefix);
            int effective=Math.max(prefix,24);
            long ip=number(local.getAddress()), mask=(0xffffffffL<<(32-effective))&0xffffffffL;
            label=dotted(ip&mask)+"/"+effective+(prefix<24?"（仅扫描本机所在 /24）":"");
        }
        String identity(){return network+":"+label+":"+addresses.toString()+":"+dnsServers.toString();}
    }
    static Scope wifiScope(ConnectivityManager cm) {
        for(Network n:cm.getAllNetworks()) {
            NetworkCapabilities caps=cm.getNetworkCapabilities(n);
            LinkProperties links=cm.getLinkProperties(n);
            if(caps==null||links==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    ||!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))continue;
            for(LinkAddress link:links.getLinkAddresses())if(link.getAddress() instanceof Inet4Address) {
                Inet4Address address=(Inet4Address)link.getAddress();
                if(!address.isLoopbackAddress()&&!address.isLinkLocalAddress())
                    return new Scope(n,address,link.getPrefixLength(),links.getDnsServers());
            }
        }
        return null;
    }
    // Never scan outside the assigned subnet, and cap broad Wi-Fi networks at one /24.
    static List<String> targets(Inet4Address local,int prefix) {
        if(prefix<0||prefix>32)throw new IllegalArgumentException("IPv4 prefix");
        int p=Math.max(prefix,24);long ip=number(local.getAddress());
        long mask=(0xffffffffL<<(32-p))&0xffffffffL, base=ip&mask, end=base+(1L<<(32-p))-1;
        List<String> result=new ArrayList<>();
        long first=p>=31?base:base+1,last=p>=31?end:end-1;
        for(long a=first;a<=last;a++)if(a!=ip)result.add(dotted(a));
        return result;
    }
    private static long number(byte[] b){return ((b[0]&255L)<<24)|((b[1]&255L)<<16)|((b[2]&255L)<<8)|(b[3]&255L);}
    private static String dotted(long n){return (n>>>24&255)+"."+(n>>>16&255)+"."+(n>>>8&255)+"."+(n&255);}

    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService pool=Executors.newFixedThreadPool(WORKERS);
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final Set<DatagramSocket> dnsSockets=ConcurrentHashMap.newKeySet();
    private final Map<String,String> ptrNames=new HashMap<>();
    private final Map<String,Host> found=new TreeMap<>();
    private final Map<String,String> names=new HashMap<>();
    private final ArrayDeque<NsdServiceInfo> resolveQueue=new ArrayDeque<>();
    private final Set<String> queuedNames=new HashSet<>();
    private final Scope scope;
    private final Listener listener;
    private final NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private boolean resolving, scanning=true;
    private volatile boolean closed;
    private long started;
    LanSshDiscovery(Context context,Scope scope,Listener listener) {
        this.scope=scope;this.listener=listener;
        nsd=(NsdManager)context.getSystemService(Context.NSD_SERVICE);
    }
    void start() {
        started=android.os.SystemClock.elapsedRealtime();publish();startNames();
        AtomicInteger remaining=new AtomicInteger(scope.addresses.size());
        if(scope.addresses.isEmpty()){finish();return;}
        for(String address:scope.addresses)pool.execute(()->{
            boolean ssh=false;
            try(Socket socket=scope.network.getSocketFactory().createSocket()) {
                sockets.add(socket);
                if(!closed){socket.connect(new InetSocketAddress(address,PORT),450);ssh=hasSshBanner(socket,700);}
                sockets.remove(socket);
            }catch(IOException|SecurityException ignored){}
            // Also remove already-closed sockets on failed connections.
            sockets.removeIf(Socket::isClosed);
            boolean accepted=ssh;
            if(accepted)main.post(()->{
                if(closed||!scanning)return;
                found.put(address,new Host(address,displayName(address)));publish();
            });
            String ptr=accepted&&!closed?reverseName(address):null;
            main.post(()->{
                if(closed||!scanning)return;
                if(accepted){if(ptr!=null)ptrNames.put(address,ptr);found.put(address,new Host(address,displayName(address)));publish();}
                if(remaining.decrementAndGet()==0)main.postDelayed(this::finish,
                        Math.max(0,3500-(android.os.SystemClock.elapsedRealtime()-started)));
            });
        });
        main.postDelayed(this::finish,20000);
    }
    /** Read only a bounded server identification. An arbitrary listener on 22 is not SSH. */
    static boolean hasSshBanner(Socket socket,int timeoutMs) throws IOException {
        long deadline=android.os.SystemClock.elapsedRealtime()+timeoutMs;
        InputStream input=socket.getInputStream();StringBuilder line=new StringBuilder();
        for(int count=0;count<1024;count++) {
            long left=deadline-android.os.SystemClock.elapsedRealtime();if(left<=0)return false;
            socket.setSoTimeout((int)Math.max(1,left));int b=input.read();if(b<0)return false;
            if(b=='\n') {
                String value=line.toString();
                if(value.startsWith("SSH-"))return value.matches("SSH-(2\\.0|1\\.99)-[^\\s]+.*");
                line.setLength(0);
            }else if(b!='\r'){line.append((char)b);if(line.length()>255)return false;}
        }
        return false;
    }
    private String displayName(String address){return names.getOrDefault(address,ptrNames.getOrDefault(address,address));}
    private String reverseName(String address){
        int id=java.util.concurrent.ThreadLocalRandom.current().nextInt(65536);
        try{
            byte[] query=PtrDns.query(address,id);
            for(InetAddress dns:scope.dnsServers.subList(0,Math.min(2,scope.dnsServers.size()))){
                if(closed||Thread.currentThread().isInterrupted())return null;
                try(DatagramSocket socket=new DatagramSocket(null)){
                    dnsSockets.add(socket);
                    if(closed)return null;
                    scope.network.bindSocket(socket);socket.connect(dns,53);socket.setSoTimeout(400);
                    socket.send(new DatagramPacket(query,query.length));
                    byte[] answer=new byte[2048];DatagramPacket packet=new DatagramPacket(answer,answer.length);socket.receive(packet);
                    String name=PtrDns.answer(answer,packet.getLength(),id,query);if(name!=null)return name;
                }catch(IOException|SecurityException ignored){}finally{dnsSockets.removeIf(DatagramSocket::isClosed);}
            }
        }catch(IOException ignored){}
        return null;
    }
    private void publish(){if(!closed)listener.update(new ArrayList<>(found.values()),scanning,
            (scanning?"正在扫描 ":"扫描完成 · ")+scope.label);}
    private void finish(){
        if(closed||!scanning)return;
        scanning=false;stopWork();main.removeCallbacksAndMessages(null);publish();
    }
    private void startNames(){
        if(nsd==null)return;
        discovery=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String type){}
            public void onDiscoveryStopped(String type){}
            public void onStartDiscoveryFailed(String type,int error){main.post(()->stopNames());}
            public void onStopDiscoveryFailed(String type,int error){}
            public void onServiceLost(NsdServiceInfo info){}
            public void onServiceFound(NsdServiceInfo info){main.post(()->{
                if(closed||!scanning||queuedNames.size()>=32||!queuedNames.add(info.getServiceName()))return;
                resolveQueue.add(info);resolveNext();
            });}
        };
        try{nsd.discoverServices("_ssh._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery);}
        catch(RuntimeException ignored){stopNames();}
    }
    @SuppressWarnings("deprecation") private void resolveNext(){
        if(closed||!scanning||resolving||resolveQueue.isEmpty())return;
        resolving=true;
        try{nsd.resolveService(resolveQueue.remove(),new NsdManager.ResolveListener(){
            public void onResolveFailed(NsdServiceInfo info,int error){main.post(()->{resolving=false;resolveNext();});}
            public void onServiceResolved(NsdServiceInfo info){main.post(()->{
                resolving=false;if(closed||!scanning)return;
                InetAddress host=info.getHost();
                if(host!=null&&info.getPort()==PORT&&scope.addresses.contains(host.getHostAddress())) {
                    String address=host.getHostAddress();
                    String name=info.getServiceName().replaceAll("[\\p{Cntrl}\\p{Cf}]","");
                    if(name.length()>80)name=name.substring(0,80);
                    if(!name.isEmpty()){
                        names.put(address,name);
                        if(found.containsKey(address)){found.put(address,new Host(address,name));publish();}
                    }
                }
                resolveNext();
            });}
        });}catch(RuntimeException ignored){resolving=false;resolveNext();}
    }
    private void stopNames(){
        if(discovery!=null){try{nsd.stopServiceDiscovery(discovery);}catch(RuntimeException ignored){}discovery=null;}
        resolveQueue.clear();
    }
    private void stopWork(){
        pool.shutdownNow();for(DatagramSocket s:dnsSockets)s.close();dnsSockets.clear();for(Socket s:sockets)try{s.close();}catch(IOException ignored){}sockets.clear();stopNames();
    }
    @Override public void close(){closed=true;main.removeCallbacksAndMessages(null);stopWork();}
}
