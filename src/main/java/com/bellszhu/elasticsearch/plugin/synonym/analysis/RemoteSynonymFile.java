/**
 *
 */
package com.bellszhu.elasticsearch.plugin.synonym.analysis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.text.ParseException;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;

import java.security.AccessController;
import java.security.PrivilegedAction;

import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.elasticsearch.analysis.common.ESSolrSynonymParser;
import org.elasticsearch.analysis.common.ESWordnetSynonymParser;
import org.elasticsearch.env.Environment;

/**
 * @author bellszhu
 */
public class RemoteSynonymFile implements SynonymFile {

    private static final String LAST_MODIFIED_HEADER = "Last-Modified";
    private static final String ETAG_HEADER = "ETag";

    private static final Logger logger = LogManager.getLogger("dynamic-synonym");

    private CloseableHttpClient httpclient;

    private String format;

    private boolean expand;

    private boolean lenient;

    private Analyzer analyzer;

    private Environment env;

    /**
     * Remote URL address
     */
    private String location;

    private String lastModified;

    private String eTags;

    RemoteSynonymFile(Environment env, Analyzer analyzer,
                      boolean expand, boolean lenient, String format, String location) {
        this.analyzer = analyzer;
        this.expand = expand;
        this.lenient = lenient;
        this.format = format;
        this.env = env;
        this.location = location;

        this.httpclient = AccessController.doPrivileged((PrivilegedAction<CloseableHttpClient>) HttpClients::createDefault);

        isNeedReloadSynonymMap();
    }

    static SynonymMap.Builder getSynonymParser(
            Reader rulesReader, String format, boolean expand, boolean lenient, Analyzer analyzer
    ) throws IOException, ParseException {
        SynonymMap.Builder parser;
        if ("wordnet".equalsIgnoreCase(format)) {
            parser = new ESWordnetSynonymParser(true, expand, lenient, analyzer);
            ((ESWordnetSynonymParser) parser).parse(rulesReader);
        } else {
            parser = new ESSolrSynonymParser(true, expand, lenient, analyzer);
            ((ESSolrSynonymParser) parser).parse(rulesReader);
        }
        return parser;
    }

    @Override
    public SynonymMap reloadSynonymMap() {
        Reader rulesReader = null;
        try {
            logger.debug("start reload remote synonym from {}.", location);
            rulesReader = getReader();
            SynonymMap.Builder parser;

            parser = getSynonymParser(rulesReader, format, expand, lenient, analyzer);
            return parser.build();
        } catch (Exception e) {
            logger.error("reload remote synonym {} error!", location, e);
            throw new IllegalArgumentException(
                    "could not reload remote synonyms file to build synonyms",
                    e);
        } finally {
            if (rulesReader != null) {
                try {
                    rulesReader.close();
                } catch (Exception e) {
                    logger.error("failed to close rulesReader", e);
                }
            }
        }
    }

    private CloseableHttpResponse executeHttpRequest(HttpUriRequest httpUriRequest) {
        return AccessController.doPrivileged((PrivilegedAction<CloseableHttpResponse>) () -> {
            try {
                return httpclient.execute(httpUriRequest);
            } catch (IOException e) {
                logger.error("Unable to execute HTTP request.", e);
            }
            return null;
        });
    }

    /**
     * Download custom terms from a remote server
     */
    public Reader getReader() {
        Reader reader;
        RequestConfig rc = RequestConfig.custom()
                .setConnectionRequestTimeout(10 * 1000)
                .setConnectTimeout(10 * 1000).setSocketTimeout(60 * 1000)
                .build();
        CloseableHttpResponse response = null;
        BufferedReader br = null;
        HttpGet get = new HttpGet(location);
        get.setConfig(rc);
        try {
            response = executeHttpRequest(get);
            if (response.getStatusLine().getStatusCode() == 200) {
                String charset = "UTF-8"; // 获取编码，默认为utf-8
                if (response.getEntity().getContentType().getValue()
                        .contains("charset=")) {
                    String contentType = response.getEntity().getContentType()
                            .getValue();
                    charset = contentType.substring(contentType
                            .lastIndexOf('=') + 1);
                }

                br = new BufferedReader(new InputStreamReader(response
                        .getEntity().getContent(), charset));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    logger.debug("reload remote synonym: {}", line);
                    sb.append(line)
                            .append(System.getProperty("line.separator"));
                }
                reader = new StringReader(sb.toString());
            } else reader = new StringReader("");
        } catch (Exception e) {
            logger.error("get remote synonym reader {} error!", location, e);
//            throw new IllegalArgumentException(
//                    "Exception while reading remote synonyms file", e);
            // Fix #54 Returns blank if synonym file has be deleted.
            reader = new StringReader("");
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (IOException e) {
                logger.error("failed to close bufferedReader", e);
            }
            try {
                if (response != null) {
                    response.close();
                }
            } catch (IOException e) {
                logger.error("failed to close http response", e);
            }
        }
        return reader;
    }

    @Override
    public boolean isNeedReloadSynonymMap() {
        RequestConfig rc = RequestConfig.custom()
                .setConnectionRequestTimeout(10 * 1000)
                .setConnectTimeout(10 * 1000).setSocketTimeout(15 * 1000)
                .build();
//        HttpHead head = new HttpHead(location);
//        head.setConfig(rc);
        HttpGet get = new HttpGet(location);
        get.setConfig(rc);

        // 设置请求头
        if (lastModified != null) {
            get.setHeader("If-Modified-Since", lastModified);
        }
        if (eTags != null) {
            get.setHeader("If-None-Match", eTags);
        }


        CloseableHttpResponse response = null;
        try {
            response = executeHttpRequest(get);
            logger.debug("start check is need reload synonym api 1");
            if (response.getStatusLine().getStatusCode() == 200) { // 返回200 才做操作
                String lastModifiedTmp = response.getLastHeader(LAST_MODIFIED_HEADER) == null ? null
                        : response.getLastHeader(LAST_MODIFIED_HEADER)
                        .getValue();
                String eTagsTmp = response.getLastHeader(ETAG_HEADER) == null ? null
                        : response.getLastHeader(ETAG_HEADER).getValue();
                if ((lastModifiedTmp != null && !lastModifiedTmp.equalsIgnoreCase(lastModified))
                        || (eTagsTmp != null && !eTagsTmp.equalsIgnoreCase(eTags))) {

                    lastModified = lastModifiedTmp;
                    eTags = eTagsTmp;
                    logger.debug("start check is need reload synonym api 2");
                    return true;
                }
            } else if (response.getStatusLine().getStatusCode() == 304) {
                return false;
            } else {
                logger.info("remote synonym {} return bad code {}", location,
                        response.getStatusLine().getStatusCode());
            }
        } catch (Exception e) {
            logger.error("request api {} error!", location, e);
        }finally {
            try {
                if (response != null) {
                    response.close();
                }
            } catch (IOException e) {
                logger.error("failed to close http response", e);
            }
        }
        return false;
    }
}
