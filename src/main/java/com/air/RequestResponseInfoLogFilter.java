

package com.air;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

/**
 * 记录http请求的参数/响应结果/耗时
 * todo async servlet 支持
 *
 * 支持配置的属性：
 *  1. whitePatterns： 打印请求的响应白名单，正则表达式，以分号分隔
 *  2. logResp: 是否打印请求的响应， 默认是false
 *
 * @date 18/3/24
 */
@WebFilter(filterName = "httpLogFilter", urlPatterns = "/*")
public class RequestResponseInfoLogFilter implements Filter {

    private static final Logger REQUEST_RESPONSE_LOGGER = LoggerFactory.getLogger("http.request.response.log");

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestResponseInfoLogFilter.class);

    private static final String SEP = System.lineSeparator();

    private FilterConfig filterConfig;

    private Set<Pattern> whitePatternSet = Sets.newHashSet();

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        this.filterConfig = filterConfig;
        Optional.ofNullable(filterConfig)
            .ifPresent(config -> {
                final String whitePatternString = config.getInitParameter("whitePatterns");
                if (whitePatternString == null || whitePatternString.isEmpty()) {
                    return;
                }
                final String[] patternArray = whitePatternString.split(";");
                whitePatternSet = Arrays.stream(patternArray)
                    .parallel()
                    .map(p -> {
                        try {
                            return Pattern.compile(p);
                        } catch (Throwable t) {
                            LOGGER.error("error compiling pattern {}", p, t);
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            });

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("init request response info log filter, filteronfig={}, white pattern={}", filterConfig, whitePatternSet);
        }
    }

    private boolean logResponse(HttpServletRequest httpServletRequest) {
        final String logResp = filterConfig.getInitParameter("logResp");
        return true || Boolean.parseBoolean(logResp) || isInWhite(httpServletRequest);
    }

    /**
     * 判断请求的request uri是否在白名单里
     * @param httpServletRequest
     * @return
     */
    private boolean isInWhite(HttpServletRequest httpServletRequest) {
        final String requestURI = httpServletRequest.getRequestURI();
        if (whitePatternSet == null || whitePatternSet.isEmpty()) {
            return false;
        }
        return whitePatternSet.parallelStream()
            .anyMatch(p -> {
                try {
                    return p.matcher(requestURI)
                        .matches();
                } catch (Throwable ignore) {
                    LOGGER.warn("error match {}, pattern={}", requestURI, p);
                }
                return false;
            });
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
        throws IOException, ServletException {

        HttpServletRequest httpServletRequest = (HttpServletRequest)servletRequest;

        // 开启log response才进行wrap，减少性能损失
        HttpServletResponse wrappedHttpServletResponse =
            logResponse(httpServletRequest) ? new WrappedHttpServletResponse((HttpServletResponse)servletResponse) :
                (HttpServletResponse)servletResponse;

        StringBuilder requestInfoBuilder = new StringBuilder();
        // 收集 request uri, header
        collectBaseInfo(httpServletRequest, requestInfoBuilder);

        long requestStartAt = System.currentTimeMillis();

        // 对于每种http请求都必须处理，即调用 filterChain.doFilter()， 否则请求就被直接drop了
        String contentType = httpServletRequest.getContentType();
        if (StringUtils.startsWith(contentType, ContentType.APPLICATION_JSON.getMimeType())) {
            AlwaysReadableRequest alwaysReadableRequest = new AlwaysReadableRequest(httpServletRequest);
            collectJsonParam(requestInfoBuilder, alwaysReadableRequest);
            filterAndLog(filterChain, alwaysReadableRequest, wrappedHttpServletResponse, requestInfoBuilder,
                requestStartAt);
            return;
        }

        if (StringUtils.startsWith(contentType, ContentType.MULTIPART_FORM_DATA.getMimeType())) {
            // 这里如果查询了参数，在 controller 里拿不到 file，先只记录是多表单
            requestInfoBuilder.append("[multipart/form-data]");
        }

        // default 处理分支
        filterAndLog(filterChain, httpServletRequest, wrappedHttpServletResponse, requestInfoBuilder, requestStartAt);
    }

    private void filterAndLog(FilterChain filterChain, HttpServletRequest httpServletRequest,
        HttpServletResponse wrappedHttpServletResponse, StringBuilder requestInfoBuilder, long requestStartAt)
        throws IOException, ServletException {
        try {
            filterChain.doFilter(httpServletRequest, wrappedHttpServletResponse);
        } catch (Throwable t) {
            LOGGER.error("exception catched from filterChain.doFilter", t);
            throw t;
        } finally {
            doLog(httpServletRequest, wrappedHttpServletResponse, requestInfoBuilder, requestStartAt);
        }
    }

    private void collectBaseInfo(HttpServletRequest httpServletRequest, StringBuilder requestInfoBuilder) {
        try {
            requestInfoBuilder.append(SEP)
                .append("ip: ")
                .append(httpServletRequest.getRemoteAddr())
                .append(SEP);
            requestInfoBuilder.append(httpServletRequest.getMethod());
            requestInfoBuilder.append(" ");
            requestInfoBuilder.append(httpServletRequest.getRequestURL());
            requestInfoBuilder.append("?");
            collectParam(httpServletRequest, requestInfoBuilder);
            collectHeader(httpServletRequest, requestInfoBuilder);
        } catch (Throwable ignore) {
            // ignore
            LOGGER.error("error occured when parsing basic param", ignore);
        }
    }

    private void collectJsonParam(StringBuilder requestInfoBuilder, AlwaysReadableRequest alwaysReadableRequest) {
        try {
            BufferedReader httpServletRequestReader = alwaysReadableRequest.getReader();
            String input;
            while ((input = httpServletRequestReader.readLine()) != null) {
                requestInfoBuilder.append(input);
            }
            requestInfoBuilder.append(SEP);
        } catch (Throwable ignore) {
            LOGGER.error("error occured when collect josn param", ignore);
        }
    }

    /**
     * 记录日志
     *
     * @param requestInfo
     * @param requestStartAt
     */
    private void doLog(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse,
        StringBuilder requestInfo, long requestStartAt) {
        final long requestEndTime = System.currentTimeMillis();
        long cost = requestEndTime - requestStartAt;
        if (!logResponse(httpServletRequest)) {
            REQUEST_RESPONSE_LOGGER.info("{}{}start time {} --> end time {}, \033[33m\033[01mcost: {}\033[0m{}",
                requestInfo.toString(), SEP, requestStartAt, requestEndTime, cost, SEP);
            return;
        }

        try {
            String responseContent = ((WrappedHttpServletResponse)httpServletResponse).getReponseContent();
            REQUEST_RESPONSE_LOGGER.info(
                "{}{}start time {} --> end time {}, \033[33m\033[01mcost: {}\033[0m{}response info: {}{}",
                requestInfo.toString(), SEP, requestStartAt, requestEndTime, cost, SEP, responseContent, SEP);
        } catch (Throwable t) {
            LOGGER.error("requestResponseLogger get response content error, request info: {}", t);
        }

    }

    /**
     * 收集请求中的header信息
     * @param httpServletRequest http请求
     * @param stringBuilder 日志builder
     */
    private void collectHeader(HttpServletRequest httpServletRequest, StringBuilder stringBuilder) {
        final Enumeration<String> headerNames = httpServletRequest.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            final String headerName = headerNames.nextElement();
            if ("content-length".equalsIgnoreCase(headerName)) {
                continue;
            }
            stringBuilder.append(headerName);
            stringBuilder.append(": ");
            final String header = httpServletRequest.getHeader(headerName);
            stringBuilder.append(header);
            stringBuilder.append(SEP);
        }
        stringBuilder.append(SEP);
    }

    /**
     * 获取参数
     *
     * @param httpServletRequest
     * @param stringBuilder
     */
    private void collectParam(HttpServletRequest httpServletRequest, StringBuilder stringBuilder) {
        Iterator<Map.Entry<String, String[]>> entryIterator = httpServletRequest.getParameterMap()
            .entrySet()
            .iterator();
        Map.Entry<String, String[]> paramEntry;
        if (entryIterator.hasNext()) {
            paramEntry = entryIterator.next();
            stringBuilder.append(paramEntry.getKey())
                .append("=")
                .append(getStringFromStringArrayEncoded(paramEntry.getValue()));
        }

        while (entryIterator.hasNext()) {
            paramEntry = entryIterator.next();
            stringBuilder.append("&");
            stringBuilder.append(paramEntry.getKey())
                .append("=")
                .append(getStringFromStringArrayEncoded(paramEntry.getValue()));
        }
        stringBuilder.append(SEP);
    }

    private String getStringFromStringArrayEncoded(String[] source) {
        final String result = getStringFromStringArray(source);
        try {
            return URLEncoder.encode(result, Charset.defaultCharset().displayName());
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return result;
    }


    private String getStringFromStringArray(String[] source) {
        if (source == null || source.length == 0) {
            return StringUtils.EMPTY;
        }
        if (source.length == 1) {
            return source[0];
        }

        return Arrays.toString(source);
    }

    @Override
    public void destroy() {

    }
}
