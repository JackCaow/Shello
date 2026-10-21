package dev.ghostty.android.sample;

/** Numeric IPv4 checks; never resolve untrusted discovery/QR input through DNS. */
final class NetworkAddress {
    private NetworkAddress() {}
    static int[] ipv4(String host){
        if(host==null||!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return null;
        String[] parts=host.split("\\.");int[] bytes=new int[4];
        for(int i=0;i<4;i++){if(parts[i].length()>1&&parts[i].startsWith("0"))return null;bytes[i]=Integer.parseInt(parts[i]);if(bytes[i]>255)return null;}
        return bytes;
    }
    static boolean tailscale(String host){int[] a=ipv4(host);return a!=null&&a[0]==100&&a[1]>=64&&a[1]<=127;}
    static boolean pairing(String host){int[] a=ipv4(host);return a!=null&&(a[0]==10||a[0]==172&&a[1]>=16&&a[1]<=31||a[0]==192&&a[1]==168||a[0]==127||tailscale(host));}
}
