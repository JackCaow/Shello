package dev.ghostty.android.sample;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Minimal bounded DNS PTR wire codec (RFC 1035); transport stays on the selected Wi-Fi. */
final class PtrDns {
    private PtrDns(){}
    static byte[] query(String address,int id) throws IOException {
        String[] parts=address.split("\\.");if(parts.length!=4)throw new IOException("IPv4 only");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeShort(id);out.writeShort(0x0100);out.writeShort(1);out.writeShort(0);out.writeShort(0);out.writeShort(0);
        for(int i=3;i>=0;i--){int n=Integer.parseInt(parts[i]);if(n<0||n>255)throw new IOException("IPv4 octet");label(out,Integer.toString(n));}
        label(out,"in-addr");label(out,"arpa");out.writeByte(0);out.writeShort(12);out.writeShort(1);return bytes.toByteArray();
    }
    private static void label(DataOutputStream out,String value)throws IOException{out.writeByte(value.length());out.write(value.getBytes(StandardCharsets.US_ASCII));}
    static String answer(byte[] bytes,int length,int id,byte[] query)throws IOException {
        if(length<12||length>bytes.length)return null;
        Reader r=new Reader(bytes,length);
        if(r.u16(0)!=id||(r.u16(2)&0xfa0f)!=0x8000||r.u16(4)!=1)return null; // response, standard opcode, not truncated, no error
        int[] pos={12};String question=r.name(pos);
        Reader q=new Reader(query,query.length);int[] qp={12};String expected=q.name(qp);
        if(!question.equalsIgnoreCase(expected)||r.u16(pos[0])!=12||r.u16(pos[0]+2)!=1)return null;
        pos[0]+=4;
        int count=Math.min(r.u16(6),64);
        for(int i=0;i<count;i++) {
            String owner=r.name(pos);int type=r.u16(pos[0]),clazz=r.u16(pos[0]+2),size=r.u16(pos[0]+8);
            int start=pos[0]+10,end=start+size;if(end>length)throw new IOException("Truncated record");pos[0]=end;
            if(type==12&&clazz==1&&owner.equalsIgnoreCase(expected)){
                int[] target={start};String name=r.name(target);
                if(target[0]>end)throw new IOException("Invalid PTR length");
                if(name.length()>253||!name.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*"))return null;
                return name;
            }
        }
        return null;
    }
    private static final class Reader {
        final byte[] b;final int limit;
        Reader(byte[] b,int limit){this.b=b;this.limit=limit;}
        int u16(int p)throws IOException{if(p<0||p+2>limit)throw new IOException("Truncated DNS");return (b[p]&255)<<8|(b[p+1]&255);}
        String name(int[] pos)throws IOException{
            int p=pos[0],consumed=-1;StringBuilder name=new StringBuilder();
            for(int hops=0;hops<128;hops++){
                if(p<0||p>=limit)throw new IOException("DNS name bounds");int n=b[p++]&255;
                if((n&0xc0)==0xc0){if(p>=limit)throw new IOException("DNS pointer");if(consumed<0)consumed=p+1;p=((n&63)<<8)|(b[p]&255);continue;}
                if(n==0){pos[0]=consumed<0?p:consumed;return name.toString();}
                if(n>63||p+n>limit)throw new IOException("DNS label");
                if(name.length()>0)name.append('.');
                for(int i=0;i<n;i++){int c=b[p++]&255;if(c<33||c>126)throw new IOException("DNS label character");name.append((char)c);}
                if(name.length()>253)throw new IOException("DNS name length");
            }
            throw new IOException("DNS compression cycle");
        }
    }
}
