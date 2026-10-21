package dev.ghostty.android.sample;

import org.json.*;
import android.net.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class TailscaleDiscoveryTest {
    @Test public void pairingRejectsPublicAddressesAndAcceptsTailnetBoundaries(){
        for(String ip:new String[]{"100.64.0.1","100.127.255.254","10.0.0.1","192.168.15.191","172.31.1.1","127.0.0.1"})assertTrue(ip,NetworkAddress.pairing(ip));
        for(String ip:new String[]{"100.63.255.255","100.128.0.0","100.064.1.1","100.64.1.256","8.8.8.8","host.ts.net","fd7a:115c:a1e0::1","0.0.0.0","169.254.1.1"})assertFalse(ip,NetworkAddress.pairing(ip));
        String qr="ghostty-pair://v1?pin="+String.join("",Collections.nCopies(64,"a"))+"&token="+String.join("",Collections.nCopies(40,"b"))+"&port=8765&expires="+(System.currentTimeMillis()/1000+120)+"&host=";
        assertEquals("100.83.228.61",new PairingClient.Ticket(qr+"100.83.228.61").host);
        try{new PairingClient.Ticket(qr+"100.128.0.1");fail("Public QR accepted");}catch(IllegalArgumentException expected){}
    }
    @Test public void routesNeverExpandSubnetOrProbeSelfOrDns()throws Exception{
        Set<String> local=Collections.singleton("100.75.137.58");
        assertTrue(TailscaleSshDiscovery.routeCandidate(prefix("100.83.228.61/32"),RouteInfo.RTN_UNICAST,local));
        for(String prefix:new String[]{"100.64.0.0/10","0.0.0.0/0","100.75.137.58/32","100.100.100.100/32","192.168.1.2/32","fd7a:115c:a1e0::1/128"})
            assertFalse(prefix,TailscaleSshDiscovery.routeCandidate(prefix(prefix),RouteInfo.RTN_UNICAST,local));
        assertFalse(TailscaleSshDiscovery.routeCandidate(prefix("100.83.228.61/32"),RouteInfo.RTN_UNREACHABLE,local));
    }
    private IpPrefix prefix(String value)throws Exception{String[] p=value.split("/");return new IpPrefix(java.net.InetAddress.getByName(p[0]),Integer.parseInt(p[1]));}
    private JSONObject peer(String name,boolean online,String... ips)throws Exception{return new JSONObject().put("HostName",name).put("Online",online).put("TailscaleIPs",new JSONArray(Arrays.asList(ips)));}
    @Test public void peersAreOnlineBoundedDeduplicatedAndNeverPublic()throws Exception{
        JSONObject data=new JSONObject().put("BackendState","Running").put("Self",peer("Mac",false,"100.64.1.2","fd7a:115c:a1e0::1"));
        JSONObject peers=new JSONObject().put("one",peer("offline",false,"100.64.1.3"))
            .put("two",peer("bad\n\u202ename",true,"8.8.8.8","100.64.1.4","100.100.100.100"))
            .put("three",peer("Mac",true,"100.64.1.2"));data.put("Peer",peers);
        List<LanSshDiscovery.Host> found=TailscaleSshDiscovery.parse(data.toString());assertEquals(2,found.size());
        for(LanSshDiscovery.Host h:found){assertTrue(NetworkAddress.tailscale(h.address));assertFalse(h.name.contains("\n"));assertFalse(h.name.contains("\u202e"));}
        data.put("BackendState","Stopped");assertTrue(TailscaleSshDiscovery.parse(data.toString()).isEmpty());
        data.put("BackendState","Running");for(int i=0;i<200;i++)peers.put("n"+i,peer("node",true,"100.65.0."+(i+1)));
        assertEquals(128,TailscaleSshDiscovery.parse(data.toString()).size());
    }
}
