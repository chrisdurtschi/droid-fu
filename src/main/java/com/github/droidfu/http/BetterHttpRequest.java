/* Copyright (c) 2009 Matthias Kaeppler
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.droidfu.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import oauth.signpost.OAuthConsumer;
import oauth.signpost.exception.OAuthException;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.impl.client.RequestWrapper;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;

import android.util.Log;

public abstract class BetterHttpRequest {

    private static final String LOG_TAG = BetterHttpRequest.class.getSimpleName();

    private static final int MAX_RETRIES = 5;
    private static final int RETRY_SLEEP_TIME_MILLIS = 3 * 1000;
    private static final String REQUEST_URI_BACKUP = "request_uri_backup";

    protected static final String HTTP_CONTENT_TYPE_HEADER = "Content-Type";

    protected List<Integer> expectedStatusCodes = new ArrayList<Integer>();

    protected OAuthConsumer oauthConsumer;

    protected AbstractHttpClient httpClient;

    protected HttpUriRequest request;

    private ResponseHandler<BetterHttpResponse> responseHandler = new ResponseHandler<BetterHttpResponse>() {
        public BetterHttpResponse handleResponse(HttpResponse response)
                throws ClientProtocolException, IOException {
            return BetterHttpRequest.this.handleResponse(response);
        }
    };

    private HttpRequestRetryHandler retryHandler = new HttpRequestRetryHandler() {

        public boolean retryRequest(IOException exception, int executionCount, HttpContext context) {
            return retryRequest(exception, executionCount, context);
        }
    };

    BetterHttpRequest(AbstractHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public HttpUriRequest unwrap() {
        return request;
    }

    public BetterHttpRequest expecting(Integer... statusCodes) {
        expectedStatusCodes = Arrays.asList(statusCodes);
        return this;
    }

    public BetterHttpRequest signed(OAuthConsumer oauthConsumer) throws OAuthException {
        this.oauthConsumer = oauthConsumer;
        oauthConsumer.sign(this.unwrap());
        return this;
    }

    public BetterHttpResponse send() throws ConnectException {

        BetterHttp.updateProxySettings();

        HttpContext httpContext = new BasicHttpContext();
        // Apache HttpClient rewrites the request URI to be relative before
        // sending a request, but we need the full URI in the retry handler,
        // so store it manually before proceeding.
        httpContext.setAttribute(REQUEST_URI_BACKUP, request.getURI());

        httpClient.setHttpRequestRetryHandler(retryHandler);

        int numAttempts = 0;

        while (numAttempts < MAX_RETRIES) {

            numAttempts++;

            try {
                if (oauthConsumer != null) {
                    oauthConsumer.sign(request);
                }
                return httpClient.execute(request, responseHandler, httpContext);
            } catch (Exception e) {
                waitAndContinue(e, numAttempts, MAX_RETRIES);
            }
        }
        return null;
    }

    protected void waitAndContinue(Exception cause, int numAttempts, int maxAttempts)
            throws ConnectException {
        if (numAttempts == maxAttempts) {
            Log.e(LOG_TAG, "request failed after " + numAttempts + " attempts");
            ConnectException ex = new ConnectException();
            ex.initCause(cause);
            throw ex;
        } else {
            cause.printStackTrace();
            Log.e(LOG_TAG, "request failed, will retry after " + RETRY_SLEEP_TIME_MILLIS / 1000
                    + " secs...");
            try {
                Thread.sleep(RETRY_SLEEP_TIME_MILLIS);
            } catch (InterruptedException e1) {
            }
        }
    }

    protected BetterHttpResponse handleResponse(HttpResponse response) throws IOException {
        int status = response.getStatusLine().getStatusCode();
        if (expectedStatusCodes != null && !expectedStatusCodes.contains(status)) {
            throw new HttpResponseException(status, "Unexpected status code: " + status);
        }

        return new BetterHttpResponse(response);
    }

    protected boolean retryRequest(IOException exception, int executionCount, HttpContext context) {
        if (executionCount > MAX_RETRIES) {
            return false;
        }

        exception.printStackTrace();
        Log.d(BetterHttpRequest.class.getSimpleName(), "Retrying "
                + request.getRequestLine().getUri() + " (tried: " + executionCount + " times)");

        // Apache HttpClient rewrites the request URI to be relative
        // before
        // sending a request, but we need the full URI for OAuth
        // signing,
        // so restore it before proceeding.
        RequestWrapper request = (RequestWrapper) context.getAttribute(ExecutionContext.HTTP_REQUEST);
        URI rewrittenUri = request.getURI();
        URI originalUri = (URI) context.getAttribute(REQUEST_URI_BACKUP);
        request.setURI(originalUri);

        // re-sign the request, otherwise this may yield 401s
        if (oauthConsumer != null) {
            try {
                oauthConsumer.sign(request);
            } catch (Exception e) {
                e.printStackTrace();
                // no reason to retry this
                return false;
            }
        }

        // restore URI to whatever Apache HttpClient expects
        request.setURI(rewrittenUri);

        return true;
    }
}
