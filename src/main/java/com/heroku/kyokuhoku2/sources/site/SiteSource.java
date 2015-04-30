package com.heroku.kyokuhoku2.sources.site;

import com.heroku.kyokuhoku2.HttpUtil;
import com.heroku.kyokuhoku2.actions.ActionBroker;
import com.heroku.kyokuhoku2.sources.Source;
import java.util.Arrays;
import java.util.Objects;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public abstract class SiteSource extends Source {

    protected String html = "";
    protected String[] htmlArray = new String[]{};
    protected Integer hash = null;
    protected String sourceKind = "yahoo.top";
    protected String periodExpression = "1m";
    protected String sourceUrl = "http://www.yahoo.co.jp/";
    protected String continueQuery = "";
    protected String continueKey = "";
    protected String continueValue = "";
    protected boolean singlePage = true;

    @Override
    public void configure() throws Exception {
        fromF("timer:site.%s.timer?period=%s", sourceKind, periodExpression)
                .toF("seda:site.%s.get", sourceKind);

        fromF("seda:site.%s.get", sourceKind)
                .choice().when(constant(singlePage)).process(HttpUtil.getHtmlProcessor(sourceUrl))
                .otherwise().process(HttpUtil.getPagesProcessor(sourceUrl, continueQuery, continueKey, continueValue))
                .end()
                .choice().when(validateUpcomingSiteSource(false))
                .toF("seda:site.%s.get.retry", sourceKind)
                .otherwise()
                .filter(isSiteChanging())
                .toF("seda:site.%s.changing", sourceKind);

        fromF("seda:site.%s.get.retry", sourceKind)
                .process(setDelay())
                .delay(simple("${header.myDelay}"))
                .filter(isNotUpToDateSourcePredicate())
                .toF("seda:site.%s.get", sourceKind);

        fromF("seda:site.%s.changing", sourceKind)
                .toF("log:site.%s.log.changing?showBody=false", sourceKind)
                .process(turnOtherSourceToNotUpToDate())
                .setBody(constant(onChangeActionClasses()))
                .to(ActionBroker.ENTRY_ENDPOINT);
    }

    protected Predicate isSiteChanging() {
        return new Predicate() {

            @Override
            public boolean matches(Exchange exchange) {
                boolean siteChanging = false;
                Object body = exchange.getIn().getBody();
                if (body instanceof String) {
                    String newHtml = (String) body;
                    Integer newHash = newHtml.hashCode();
                    if (!Objects.equals(newHash, hash())) {
                        siteChanging = hash() != null;
                        html(newHtml);
                        hash(newHash);
                    }
                } else if (body instanceof String[]) {
                    String[] newHtmlArray = (String[]) body;
                    Integer newHash = Arrays.hashCode(newHtmlArray);
                    if (newHash != 1 && !Objects.equals(newHash, hash())) {
                        siteChanging = hash() != null;
                        htmlArray(newHtmlArray);
                        hash(newHash);
                    }
                }
                return siteChanging;
            }
        };
    }

    protected void html(String html) {
        this.html = html;
    }

    public String html() {
        return html;
    }

    protected void htmlArray(String[] htmlArray) {
        this.htmlArray = htmlArray;
    }

    public String[] htmlArray() {
        return htmlArray;
    }

    protected void hash(int hash) {
        this.hash = hash;
    }

    protected Integer hash() {
        return hash;
    }

    public Document document() {
        return Jsoup.parse(this.html);
    }

    public Document[] documentArray() {
        Document[] documentArray = new Document[htmlArray.length];
        for (int i = 0; i < htmlArray.length; i++) {
            documentArray[i] = Jsoup.parse(htmlArray[i]);
        }
        return documentArray;
    }

    public Processor setDelay() {
        return new Processor() {

            @Override
            public void process(Exchange exchange) throws Exception {
                Long delay = exchange.getIn().getHeader("myDelay", Long.class);
                if (delay == null) {
                    delay = 1000L;
                }
                delay *= 2;
                exchange.getIn().setHeader("myDelay", delay);
            }
        };
    }

    public Predicate validateUpcomingSiteSource(final boolean flag) {
        return new Predicate() {

            @Override
            public boolean matches(Exchange exchange) {
                Object body = exchange.getIn().getBody();
                boolean validate = true;
                if (body == null) {
                    validate = false;
                } else if (body instanceof String) {
                    String newHtml = (String) body;
                    if (newHtml.isEmpty()) {
                        validate = false;
                    }
                } else if (body instanceof String[]) {
                    String[] newHtmlArray = (String[]) body;
                    for (String newHtml : newHtmlArray) {
                        if (newHtml == null) {
                            validate = false;
                            break;
                        }
                    }
                }
                if (validate) {
                    upToDate();
                } else {
                    notUpToDate();
                }
                return validate == flag;
            }
        };
    }
}