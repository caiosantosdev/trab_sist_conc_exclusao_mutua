package org.cefet.sist_conc_dist.enums;

public class Message {
    int type, pid;
    public Message(int t, int p) {
        type = t; pid = p;
    }

    public int getPid(){
        return this.pid;
    }

    public int getType(){
        return this.type;
    }

}