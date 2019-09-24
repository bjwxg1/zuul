/*
 * Copyright 2013 Netflix, Inc.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 */
package com.netflix.zuul.http;

import com.netflix.zuul.FilterProcessor;
import com.netflix.zuul.ZuulRunner;
import com.netflix.zuul.context.RequestContext;
import com.netflix.zuul.exception.ZuulException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.runners.MockitoJUnitRunner;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Core Zuul servlet which intializes and orchestrates zuulFilter execution
 *
 * @author Mikey Cohen
 *         Date: 12/23/11
 *         Time: 10:44 AM
 */

/**
 * 在Zuul升级为NIO之前，是通过Servlet作为服务入口的；在ZuulServlet内部通过ZuulRunner来运行不同类型的Filter，一个Http请求
 * 需要进过pre、route和post三种类型的Filter
 */
public class ZuulServlet extends HttpServlet {

    private static final long serialVersionUID = -3374242278843351500L;
    private ZuulRunner zuulRunner;

    /**
     * 重写了Init方法，在ZuulServlet创建后，用于初始化ZuulServlet
     * @param config
     * @throws ServletException
     */
    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        String bufferReqsStr = config.getInitParameter("buffer-requests");
        boolean bufferReqs = bufferReqsStr != null && bufferReqsStr.equals("true") ? true : false;
        //创建ZuulRunner
        zuulRunner = new ZuulRunner(bufferReqs);
    }

    /**
     * 重写service（）方法，所有的Http请求都会经过此处进行处理
     * @param servletRequest
     * @param servletResponse
     * @throws ServletException
     * @throws IOException
     */
    @Override
    public void service(javax.servlet.ServletRequest servletRequest, javax.servlet.ServletResponse servletResponse) throws ServletException, IOException {
        try {
            //将servletRequest和servletResponse绑定到RequestContext
            init((HttpServletRequest) servletRequest, (HttpServletResponse) servletResponse);

            // Marks this request as having passed through the "Zuul engine", as opposed to servlets
            // explicitly bound in web.xml, for which requests will not have the same data attached
            RequestContext context = RequestContext.getCurrentContext();
            //标记请求经过了Zuul引擎
            context.setZuulEngineRan();

            try {
                //执行preFilter
                preRoute();
            } catch (ZuulException e) {
                //如果出现异常则运行errorFilter和postFilter
                error(e);
                postRoute();
                return;
            }
            try {
                //运行routeFilter
                route();
            } catch (ZuulException e) {
                error(e);
                postRoute();
                return;
            }
            try {
                //执行postFilter
                postRoute();
            } catch (ZuulException e) {
                error(e);
                return;
            }

        } catch (Throwable e) {
            error(new ZuulException(e, 500, "UNHANDLED_EXCEPTION_" + e.getClass().getName()));
        } finally {
            RequestContext.getCurrentContext().unset();
        }
    }

    /**
     * executes "post" ZuulFilters
     *
     * @throws ZuulException
     */
    void postRoute() throws ZuulException {
        zuulRunner.postRoute();
    }

    /**
     * executes "route" filters
     *
     * @throws ZuulException
     */
    void route() throws ZuulException {
        zuulRunner.route();
    }

    /**
     * executes "pre" filters
     *
     * @throws ZuulException
     */
    void preRoute() throws ZuulException {
        zuulRunner.preRoute();
    }

    /**
     * initializes request
     *
     * @param servletRequest
     * @param servletResponse
     */
    void init(HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        zuulRunner.init(servletRequest, servletResponse);
    }

    /**
     * sets error context info and executes "error" filters
     *
     * @param e
     */
    void error(ZuulException e) {
        RequestContext.getCurrentContext().setThrowable(e);
        zuulRunner.error();
    }

    @RunWith(MockitoJUnitRunner.class)
    public static class UnitTest {

        @Mock
        HttpServletRequest servletRequest;
        @Mock
        HttpServletResponseWrapper servletResponse;
        @Mock
        FilterProcessor processor;
        @Mock
        PrintWriter writer;

        @Before
        public void before() {
            MockitoAnnotations.initMocks(this);
        }

        @Test
        public void testProcessZuulFilter() {

            ZuulServlet zuulServlet = new ZuulServlet();
            zuulServlet = spy(zuulServlet);
            RequestContext context = spy(RequestContext.getCurrentContext());


            try {
                FilterProcessor.setProcessor(processor);
                RequestContext.testSetCurrentContext(context);
                when(servletResponse.getWriter()).thenReturn(writer);

                zuulServlet.init(servletRequest, servletResponse);
                verify(zuulServlet, times(1)).init(servletRequest, servletResponse);
                assertTrue(RequestContext.getCurrentContext().getRequest() instanceof HttpServletRequestWrapper);
                assertTrue(RequestContext.getCurrentContext().getResponse() instanceof HttpServletResponseWrapper);

                zuulServlet.preRoute();
                verify(processor, times(1)).preRoute();

                zuulServlet.postRoute();
                verify(processor, times(1)).postRoute();
//                verify(context, times(1)).unset();

                zuulServlet.route();
                verify(processor, times(1)).route();
                RequestContext.testSetCurrentContext(null);

            } catch (Exception e) {
                e.printStackTrace();
            }


        }
    }

}
