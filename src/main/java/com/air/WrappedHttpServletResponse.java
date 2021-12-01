package com.air;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 备份response stream中的内容用于记录日志
 *
 * @date: 2018/08/02
 */
public class WrappedHttpServletResponse extends HttpServletResponseWrapper {

    private static final Logger LOGGER = LoggerFactory.getLogger(WrappedHttpServletResponse.class);

    private PrintWriter proxyPrintWriter;

    private ServletOutputStream proxyOutputStream;

    private ByteArrayOutputStream buffer;

    /**
     * Constructs a response adaptor wrapping the given response.
     *
     * @param response
     * @throws IllegalArgumentException if the response is null
     */
    WrappedHttpServletResponse(HttpServletResponse response) throws IOException {
        super(response);

        buffer = new ByteArrayOutputStream(1024);
        proxyOutputStream = new ServletOutputStream() {

            @Override
            public boolean isReady() {
                return false;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {

            }

            @Override
            public void write(int b) throws IOException {
                getResponse().getOutputStream().write(b);
                buffer.write(b);
            }
        };
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (proxyPrintWriter == null) {
            proxyPrintWriter = new ProxyPrintWriter(buffer, getResponse().getWriter());
        }
        return proxyPrintWriter;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        return proxyOutputStream;
    }

    String getReponseContent() throws UnsupportedEncodingException {
        return buffer.toString(getResponse().getCharacterEncoding());
    }

    private class ProxyPrintWriter extends PrintWriter {

        private ByteArrayOutputStream buffer;

        /**
         * @param buffer
         * @param printWriter
         */
        ProxyPrintWriter(ByteArrayOutputStream buffer, PrintWriter printWriter) {
            super(printWriter);
            this.buffer = buffer;
        }

        @Override
        public void write(int c) {
            super.write(c);
            buffer.write(c);
        }

        @Override
        public void write(char[] buf, int off, int len) {
            super.write(buf, off, len);
            buffer.write(toByteArrayQuietly(String.valueOf(buf), getCharacterEncoding()), off, len);
        }

        @Override
        public void write(char[] buf) {
            super.write(buf);
            buffer.write(toByteArrayQuietly(String.valueOf(buf), getCharacterEncoding()), 0, buf.length);
        }

        @Override
        public void write(String s, int off, int len) {
            super.write(s, off, len);
            buffer.write(toByteArrayQuietly(s, getCharacterEncoding()), off, len);
        }

        @Override
        public void write(String s) {
            super.write(s);
            buffer.write(toByteArrayQuietly(s, getCharacterEncoding()), 0, s.length());
        }
    }

    private byte[] toByteArrayQuietly(String string, String charset) {
        try {
            if (string == null) {
                return new byte[0];
            }
            return string.getBytes(charset);
        } catch (UnsupportedEncodingException e) {
            LOGGER.error("convert string to byte[] error, string = {}, charset = {}", string, charset);
            return new byte[0];
        }
    }
}
