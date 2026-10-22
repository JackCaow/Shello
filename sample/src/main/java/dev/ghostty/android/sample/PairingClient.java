package dev.ghostty.android.sample;

import android.net.Uri;
import android.content.Context;
import com.jcraft.jsch.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;

/** QR pins the ephemeral TLS certificate; the one-time token authorizes only a public-key append. */
final class PairingClient {
    static final class Ticket {
        final String host,pin,token;final int port;final long expires;
        Ticket(String text){
            if(text.length()>4096)throw new IllegalArgumentException("Invalid QR");Uri uri=Uri.parse(text);
            if(!"ghostty-pair".equals(uri.getScheme())||!"v1".equals(uri.getHost()))throw new IllegalArgumentException("Not a Shello pairing QR");
            host=uri.getQueryParameter("host");pin=uri.getQueryParameter("pin");token=uri.getQueryParameter("token");port=Integer.parseInt(uri.getQueryParameter("port"));expires=Long.parseLong(uri.getQueryParameter("expires"));
            if(host==null||!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")||pin==null||!pin.matches("[0-9a-f]{64}")||token==null||!token.matches("[A-Za-z0-9_-]{32,128}")||port<1||port>65535)throw new IllegalArgumentException("Invalid pairing QR");
            if(!NetworkAddress.pairing(host))throw new IllegalArgumentException("LAN or Tailscale IPv4 pairing only");
            if(expires<System.currentTimeMillis()/1000-10||expires>System.currentTimeMillis()/1000+900)throw new IllegalArgumentException("QR expired; check clocks and regenerate");
        }
    }
    static SshProfileStore.Draft pair(Ticket ticket)throws Exception {
        com.jcraft.jsch.KeyPair key=com.jcraft.jsch.KeyPair.genKeyPair(new JSch(),com.jcraft.jsch.KeyPair.RSA,3072);
        try{
            ByteArrayOutputStream privateKey=new ByteArrayOutputStream(),publicKey=new ByteArrayOutputStream();key.writePrivateKey(privateKey);key.writePublicKey(publicKey,"ghostty-android");
            TrustManager[] trust={new X509TrustManager(){public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException("client");}public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{try{if(c.length==0||!hex(MessageDigest.getInstance("SHA-256").digest(c[0].getEncoded())).equals(ticket.pin))throw new CertificateException("Pairing certificate mismatch");c[0].checkValidity();}catch(GeneralSecurityException e){throw new CertificateException(e);}}}};
            SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,trust,new SecureRandom());
            HttpsURLConnection connection=(HttpsURLConnection)new java.net.URL("https://"+ticket.host+":"+ticket.port+"/pair").openConnection();connection.setSSLSocketFactory(tls.getSocketFactory());
            // The QR pins the entire certificate, including the ephemeral identity; no CA/hostname fallback.
            connection.setHostnameVerifier((host,session)->{try{return host.equals(ticket.host)&&hex(MessageDigest.getInstance("SHA-256").digest(session.getPeerCertificates()[0].getEncoded())).equals(ticket.pin);}catch(Exception e){return false;}});
            connection.setConnectTimeout(7000);connection.setReadTimeout(7000);connection.setInstanceFollowRedirects(false);connection.setDoOutput(true);connection.setRequestMethod("POST");connection.setRequestProperty("Content-Type","application/json");
            JSONObject response;
            try{byte[] body=new JSONObject().put("token",ticket.token).put("publicKey",publicKey.toString("UTF-8")).toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(body.length);try(OutputStream out=connection.getOutputStream()){out.write(body);}if(connection.getResponseCode()!=200)throw new IOException("Pairing rejected or expired");try(InputStream in=connection.getInputStream()){response=new JSONObject(new String(read(in,32768),StandardCharsets.UTF_8));}}finally{connection.disconnect();}
            return responseDraft(ticket,response,privateKey.toByteArray());
        }finally{key.dispose();}
    }
    static SshProfileStore.Draft responseDraft(Ticket ticket,JSONObject response,byte[] privateKey)throws Exception {
        if(response.getInt("version")!=1)throw new IOException("Unsupported pairing response");
            SshProfileStore.Draft d=new SshProfileStore.Draft();d.host=ticket.host;d.port=response.getInt("port");d.user=response.getString("user");d.name=response.getString("name");d.auth="key";d.privateKey=privateKey.clone();d.tmux=true;
            String host=d.port==22?d.host:"["+d.host+"]:"+d.port;StringBuilder pins=new StringBuilder();JSONArray hostKeys=response.getJSONArray("hostKeys");
            for(int i=0;i<hostKeys.length();i++){String value=hostKeys.getString(i);if(!value.matches("(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)) [A-Za-z0-9+/=]+"))throw new IOException("Invalid host pin");pins.append(host).append(' ').append(value).append('\n');}d.knownHosts=pins.toString().getBytes(StandardCharsets.UTF_8);
            if(d.validate()!=null||pins.length()==0)throw new IOException("Invalid paired profile");return d;
    }
    static byte[] read(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[4096];for(int n;(n=in.read(bytes))!=-1;){if(out.size()+n>limit)throw new IOException("Too large");out.write(bytes,0,n);}return out.toByteArray();}
    static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
}
