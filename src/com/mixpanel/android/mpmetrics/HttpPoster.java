package com.mixpanel.android.mpmetrics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;

import android.util.Log;

import com.mixpanel.android.util.Base64Coder;
import com.mixpanel.android.util.StringUtils;

/* package */ class HttpPoster {

    public static enum PostResult {
        // The post was sent and understood by the Mixpanel service.
        SUCCEEDED,

        // The post couldn't be sent (for example, because there was no connectivity)
        // but might work later.
        FAILED_RECOVERABLE,

        // The post itself is bad/unsendable (for example, too big for system memory)
        // and shouldn't be retried.
        FAILED_UNRECOVERABLE
    };

    public HttpPoster(String defaultHost, String fallbackHost) {
        mDefaultHost = defaultHost;
        mFallbackHost = fallbackHost;
    }

    // Will return true only if the request was successful
    public PostResult postData(String rawMessage, String endpointPath) {
        PostResult ret;
        ByteArrayEntity entity = null;
        GZIPOutputStream gzipOutputStream = null;
        try {
            byte[] originalBytes = rawMessage.getBytes("UTF-8");
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(originalBytes.length);
            gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream);
            gzipOutputStream.write(originalBytes);
            gzipOutputStream.finish();
            try {
            	gzipOutputStream.flush();
            } catch (Exception ignore) {
            }
            entity = new ByteArrayEntity(byteArrayOutputStream.toByteArray());
            entity.setContentType(new BasicHeader(HTTP.CONTENT_TYPE, "application/x-gzip"));
        } catch (IOException e) {
            Log.e(LOGTAG, "Failed to compress message", e);
        } finally {
            if (gzipOutputStream != null) {
                try {
                    gzipOutputStream.close();
                } catch (IOException e) {
                    Log.e(LOGTAG, "Failed to free compressed json stream", e);
                }
            }
        }
        if (entity != null) {
            String defaultUrl = mDefaultHost + endpointPath;
            ret = postHttpRequest(defaultUrl, entity);
            if (ret == PostResult.FAILED_RECOVERABLE && mFallbackHost != null) {
                String fallbackUrl = mFallbackHost + endpointPath;
                if (MPConfig.DEBUG) Log.i(LOGTAG, "Retrying post with new URL: " + fallbackUrl);
                ret = postHttpRequest(fallbackUrl, entity);
            }
        } else {
            ret = PostResult.FAILED_UNRECOVERABLE;
        }

        return ret;
    }

    private PostResult postHttpRequest(String endpointUrl, HttpEntity postEntity) {
        PostResult ret = PostResult.FAILED_UNRECOVERABLE;
        HttpClient httpclient = new DefaultHttpClient();
        HttpPost httppost = new HttpPost(endpointUrl);

        try {
            httppost.setEntity(postEntity);
            HttpResponse response = httpclient.execute(httppost);
            HttpEntity entity = response.getEntity();

            if (entity != null) {
                String result = StringUtils.inputStreamToString(entity.getContent());
                if (result.equals("1\n")) {
                    ret = PostResult.SUCCEEDED;
                }
            }
        } catch (IOException e) {
            Log.i(LOGTAG, "Cannot post message to Mixpanel Servers (May Retry)", e);
            ret = PostResult.FAILED_RECOVERABLE;
        } catch (OutOfMemoryError e) {
            Log.e(LOGTAG, "Cannot post message to Mixpanel Servers, will not retry.", e);
            ret = PostResult.FAILED_UNRECOVERABLE;
        }

        return ret;
    }

    private final String mDefaultHost;
    private final String mFallbackHost;

    private static final String LOGTAG = "MixpanelAPI";
}
