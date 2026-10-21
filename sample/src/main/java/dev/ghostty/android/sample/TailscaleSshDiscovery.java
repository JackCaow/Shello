package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;
import android.content.Context;
import android.net.*;
import android.os.*;
import org.json.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Combine explicit VPN host routes and trusted SSH peer lists; probe only those addresses. */
final class TailscaleSshDiscovery implements AutoCloseable {
    static final int MAX_PEERS=128,MAX_SOURCES=4;
    static final String COMMAND="if command -v tailscale >/dev/null 2>&1; then exec tailscale status --json; fi; "
            +"for T in /Applications/Tailscale.app/Contents/MacOS/Tailscale /opt/homebrew/bin/tailscale /usr/local/bin/tailscale /usr/bin/tailscale; do "
            +"if [ -x \"$T\" ]; then exec \"$T\" status --json; fi; done; exit 127";
    private final Context context;private final LanSshDiscovery.Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService workers=Executors.newFixedThreadPool(4);
    private final Set<SshCommand> commands=ConcurrentHashMap.newKeySet();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final Map<String,LanSshDiscovery.Host> found=new TreeMap<>();
    private final Set<String> queued=new HashSet<>();
    private final Map<String,String> names=new HashMap<>();
    private volatile boolean closed;private boolean finished;private int pending;
    private int sourcesRead;
    TailscaleSshDiscovery(Context context,LanSshDiscovery.Listener listener){this.context=context.getApplicationContext();this.listener=listener;}

    static String vpnIdentity(ConnectivityManager cm){
        StringBuilder result=new StringBuilder();
        for(Network network:cm.getAllNetworks()){
            NetworkCapabilities caps=cm.getNetworkCapabilities(network);LinkProperties links=cm.getLinkProperties(network);
            if(caps==null||links==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;
            for(LinkAddress a:links.getLinkAddresses())if(NetworkAddress.tailscale(a.getAddress().getHostAddress()))result.append(network).append(':').append(a).append(':').append(links.getRoutes()).append(';');
        }
        return result.toString();
    }
    static List<LanSshDiscovery.Host> routedPeers(ConnectivityManager cm){
        Map<String,LanSshDiscovery.Host> result=new LinkedHashMap<>();
        for(Network network:cm.getAllNetworks()){
            NetworkCapabilities caps=cm.getNetworkCapabilities(network);LinkProperties links=cm.getLinkProperties(network);
            if(caps==null||links==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;
            Set<String> local=new HashSet<>();boolean tailnet=false;
            for(LinkAddress a:links.getLinkAddresses()){String ip=a.getAddress().getHostAddress();local.add(ip);tailnet|=NetworkAddress.tailscale(ip);}
            if(!tailnet)continue;
            for(RouteInfo route:links.getRoutes()){
                String ip=route.getDestination().getAddress().getHostAddress();
                // Only explicit host routes are candidates. Never expand a VPN subnet route.
                // getType became public in API 33. Earlier versions still require a real SSH banner.
                int type=Build.VERSION.SDK_INT>=33?route.getType():RouteInfo.RTN_UNICAST;
                if(routeCandidate(route.getDestination(),type,local)&&result.size()<MAX_PEERS)
                    result.put(ip,new LanSshDiscovery.Host(ip,ip));
            }
        }
        return new ArrayList<>(result.values());
    }
    static boolean routeCandidate(IpPrefix prefix,int type,Set<String> local){
        String ip=prefix.getAddress().getHostAddress();
        return type==RouteInfo.RTN_UNICAST&&prefix.getPrefixLength()==32&&peerAddress(ip)&&!local.contains(ip);
    }
    private static boolean peerAddress(String ip){return NetworkAddress.tailscale(ip)&&!"100.100.100.100".equals(ip);}
    static List<LanSshDiscovery.Host> parse(String json)throws JSONException{
        if(json.length()>1048576)throw new JSONException("Too many peers");
        JSONObject data=new JSONObject(json);Map<String,LanSshDiscovery.Host> result=new LinkedHashMap<>();
        if(!"Running".equals(data.optString("BackendState")))return new ArrayList<>();
        add(result,data.optJSONObject("Self"),true);
        JSONObject peers=data.optJSONObject("Peer");
        if(peers!=null)for(Iterator<String> it=peers.keys();it.hasNext()&&result.size()<MAX_PEERS;)add(result,peers.optJSONObject(it.next()),false);
        return new ArrayList<>(result.values());
    }
    private static void add(Map<String,LanSshDiscovery.Host> result,JSONObject peer,boolean self){
        if(peer==null||(!self&&!peer.optBoolean("Online",false)))return;
        JSONArray ips=peer.optJSONArray("TailscaleIPs");if(ips==null)return;
        String name=peer.optString("HostName","").replaceAll("[\\p{Cntrl}\\p{Cf}]","").trim();
        if(name.isEmpty())name=peer.optString("DNSName","").replaceAll("[\\p{Cntrl}\\p{Cf}]","").replaceFirst("\\.$","");
        if(name.length()>80)name=name.substring(0,80);
        for(int i=0;i<ips.length()&&result.size()<MAX_PEERS;i++){
            String ip=ips.optString(i);if(peerAddress(ip))result.put(ip,new LanSshDiscovery.Host(ip,name.isEmpty()?ip:name));
        }
    }
    void start(){
        SshProfileStore store=new SshProfileStore(context);List<SshProfileStore.Entry> profiles=store.list();
        String active=store.activeId();profiles.sort(Comparator.comparingInt(e->e.id.equals(active)?0:1));
        Set<String> endpoints=new HashSet<>();List<String> sources=new ArrayList<>();
        for(SshProfileStore.Entry e:profiles){if(sources.size()>=MAX_SOURCES)break;if(endpoints.add(e.user+"@"+e.host+":"+e.port))sources.add(e.id);}
        List<LanSshDiscovery.Host> routes=routedPeers((ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE));
        if(sources.isEmpty()&&routes.isEmpty()){finish(R.string.tailscale_pair_first);return;}
        pending=sources.size();publish(true,R.string.tailscale_discovering);
        main.postDelayed(()->finish(R.string.tailscale_scan_timeout),25000);
        for(LanSshDiscovery.Host peer:routes)if(queued.add(peer.address)){pending++;workers.execute(()->probe(peer));}
        for(String id:sources)workers.execute(()->{
            List<LanSshDiscovery.Host> peers=Collections.emptyList();boolean read=false;
            try(SshCommand command=new SshCommand()){
                commands.add(command);if(closed){command.close();return;}
                try{String output=command.run(context,id,COMMAND,null);peers=parse(output);read="Running".equals(new JSONObject(output).optString("BackendState"));}finally{commands.remove(command);}
            }catch(Exception ignored){}
            List<LanSshDiscovery.Host> candidates=peers;boolean success=read;
            main.post(()->{if(closed||finished)return;if(success)sourcesRead++;
                for(LanSshDiscovery.Host peer:candidates){
                    if(!peer.name.equals(peer.address)){names.put(peer.address,peer.name);if(found.containsKey(peer.address))found.put(peer.address,peer);}
                    if(queued.size()<MAX_PEERS&&queued.add(peer.address)){pending++;workers.execute(()->probe(peer));}
                }
                completed();});
        });
    }
    private void probe(LanSshDiscovery.Host host){
        boolean ssh=false;
        try(Socket socket=new Socket()){
            sockets.add(socket);if(closed)return;
            try{socket.connect(new InetSocketAddress(host.address,22),2000);ssh=LanSshDiscovery.hasSshBanner(socket,1200);}finally{sockets.remove(socket);}
        }catch(Exception ignored){}
        String name=ssh&&host.name.equals(host.address)?reverseName(host.address):host.name;
        LanSshDiscovery.Host resolved=new LanSshDiscovery.Host(host.address,name);
        boolean accepted=ssh;main.post(()->{if(closed||finished)return;if(accepted){found.put(host.address,new LanSshDiscovery.Host(host.address,names.getOrDefault(host.address,resolved.name)));publish(true,R.string.tailscale_discovering);}completed();});
    }
    private String reverseName(String ip){
        int id=ThreadLocalRandom.current().nextInt(65536);
        try(DatagramSocket socket=new DatagramSocket()){
            byte[] query=PtrDns.query(ip,id);socket.connect(InetAddress.getByName("100.100.100.100"),53);socket.setSoTimeout(600);
            socket.send(new DatagramPacket(query,query.length));byte[] bytes=new byte[2048];DatagramPacket reply=new DatagramPacket(bytes,bytes.length);socket.receive(reply);
            String name=PtrDns.answer(bytes,reply.getLength(),id,query);if(name!=null)return name;
        }catch(Exception ignored){}return ip;
    }
    private void completed(){if(--pending==0)finish(sourcesRead>0||!found.isEmpty()?R.string.tailscale_scan_complete:R.string.tailscale_source_unavailable);}
    private void publish(boolean running,int status){if(!closed)listener.update(new ArrayList<>(found.values()),running,tr(status));}
    private void finish(int status){if(closed||finished)return;finished=true;main.removeCallbacksAndMessages(null);stopWork();publish(false,status);}
    private void stopWork(){workers.shutdownNow();for(Socket socket:sockets)try{socket.close();}catch(Exception ignored){}sockets.clear();
        for(SshCommand command:commands)new Thread(command::close,"tailscale-discovery-close").start();commands.clear();}
    @Override public void close(){closed=true;main.removeCallbacksAndMessages(null);stopWork();}
}
