package com.fatongai.legalassistant.law.dto;

import java.util.List;

public class LawSearchRequest {
    private String q;
    private String searchType = "fullText";
    private List<String> categories;
    private List<String> statuses;
    private List<String> issuingBodies;
    private String region;
    private String sort = "relevance";
    private String matchMode = "fuzzy";
    private String keywordMode = "any";
    private String level;
    private String lawName;
    private String advLawTitle;
    private String publishDateFrom;
    private String publishDateTo;
    private String effectiveDateFrom;
    private String effectiveDateTo;
    private Boolean includeHistory = false;
    private Integer page = 1;
    private Integer size = 10;

    public String getQ() { return q; }
    public void setQ(String q) { this.q = q; }
    public String getSearchType() { return searchType; }
    public void setSearchType(String searchType) { this.searchType = searchType; }
    public List<String> getCategories() { return categories; }
    public void setCategories(List<String> categories) { this.categories = categories; }
    public List<String> getStatuses() { return statuses; }
    public void setStatuses(List<String> statuses) { this.statuses = statuses; }
    public List<String> getIssuingBodies() { return issuingBodies; }
    public void setIssuingBodies(List<String> issuingBodies) { this.issuingBodies = issuingBodies; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getSort() { return sort; }
    public void setSort(String sort) { this.sort = sort; }
    public String getMatchMode() { return matchMode; }
    public void setMatchMode(String matchMode) { this.matchMode = matchMode; }
    public String getKeywordMode() { return keywordMode; }
    public void setKeywordMode(String keywordMode) { this.keywordMode = keywordMode; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getLawName() { return lawName; }
    public void setLawName(String lawName) { this.lawName = lawName; }
    public String getAdvLawTitle() { return advLawTitle; }
    public void setAdvLawTitle(String advLawTitle) { this.advLawTitle = advLawTitle; }
    public String getPublishDateFrom() { return publishDateFrom; }
    public void setPublishDateFrom(String publishDateFrom) { this.publishDateFrom = publishDateFrom; }
    public String getPublishDateTo() { return publishDateTo; }
    public void setPublishDateTo(String publishDateTo) { this.publishDateTo = publishDateTo; }
    public String getEffectiveDateFrom() { return effectiveDateFrom; }
    public void setEffectiveDateFrom(String effectiveDateFrom) { this.effectiveDateFrom = effectiveDateFrom; }
    public String getEffectiveDateTo() { return effectiveDateTo; }
    public void setEffectiveDateTo(String effectiveDateTo) { this.effectiveDateTo = effectiveDateTo; }
    public Boolean getIncludeHistory() { return includeHistory; }
    public void setIncludeHistory(Boolean includeHistory) { this.includeHistory = includeHistory; }
    public Integer getPage() { return page; }
    public void setPage(Integer page) { this.page = page; }
    public Integer getSize() { return size; }
    public void setSize(Integer size) { this.size = size; }
}
