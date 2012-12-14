package org.webbitserver;


public abstract class Callback {
    public abstract void onMessage(byte[] bytes);
    public void onStop() {} 
    public void onStart() {}    
    public static Callback defaultCallback() {
        return new Callback() {
            public void onMessage(byte[] bytes) {
                throw new UnsupportedOperationException("callback was not registered...");
            }
        };
    }   
}
