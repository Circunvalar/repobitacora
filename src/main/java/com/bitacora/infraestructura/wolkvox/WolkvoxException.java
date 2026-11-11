package com.bitacora.infraestructura.wolkvox;

public class WolkvoxException extends RuntimeException {
    public WolkvoxException(String m) { super(m); }
    public WolkvoxException(String m, Throwable c) { super(m, c); }
}
