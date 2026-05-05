package com.fatongai.legalassistant.law.dto;

import java.util.List;

/**
 * POST /api/laws/sync 请求体。
 * <ul>
 *   <li>{@code provider=npc-flk}：从国家法律法规数据库（flk.npc.gov.cn）按列表分页拉取并入库。</li>
 *   <li>否则：按 {@link #urls} 抓取单页 HTML 并拆分条号（与原有行为一致）。</li>
 *   <li>{@link #forceResync}=true：忽略「库中已有则跳过」策略，强制重新抓取。</li>
 * </ul>
 */
public class LawSyncRequest {
    /**
     * {@code npc-flk} 表示全国人大常委会法规库公开接口；其它或未填则走 URL 抓取。
     */
    private String provider;
    /** 单页法规详情 URL（第三方站点），可多选。 */
    private List<String> urls;
    /** 列表 API 的 type 参数；可与英文逗号分隔多个关键字（宪法、行政法规、地方…）。 */
    private String npcType;
    /**
     * 与前端「法律法规分类」筛选一致（如 宪法、民法）；将与 npcType 一并映射为官网 {@code flfgCodeId}。
     */
    private List<String> npcCategoryLabels;
    /**
     * 未显式选分类时：{@code national} / {@code law} / {@code law-only} 仅「法律」整支（与检索页侧边栏勾选「法律」一致，不含宪法、行政法规、司法解释）；{@code national-broad}：另含宪法、行政法规、监察法规、司法解释等；{@code all}：不限站点分类。
     */
    private String npcFlfgScope;
    /**
     * 为 true 时，将当前解析出的多个 {@code flfgCodeId} 拆成「一类一页」分别请求列表后再合并（去重 bbbs），便于严格按目录桶覆盖，请求次数约为分类数×页数。
     */
    private Boolean npcIterateCatalogBuckets;
    /**
     * 单次同步覆盖配置：{@code skip} 不爬地方法规；{@code defer} 地方法规排在列表/分桶最后；{@code allow} 不限制。
     */
    private String npcLocalRegulationsPolicy;
    /**
     * 为 false 时允许爬取宪法、行政法规、司法解释等非「法律」模块（需配合 {@code npcFlfgScope=national-broad} 等）；为 true 或未传时与 {@code app.law.npc-flk.law-module-only} 一致。
     */
    private Boolean npcLawModuleOnly;
    /** 列表检索关键词（对应站点「标题/正文」检索）；空则按公布时间拉取全库分页。 */
    private String npcSearchContent;
    /** 官网检索范围：1=标题，2=正文。 */
    private Integer npcSearchRange;
    /** 官网检索方式：1=精确，2=模糊。 */
    private Integer npcSearchType;
    /** 官网效力状态：3=有效，4=尚未生效，2=已修改，1=已废止。 */
    private List<Integer> npcStatusCodes;
    private Integer npcPageStart;
    /**
     * 与 {@link #npcFetchAllListPages} 配合：为 false 时表示列表结束页（含）；为 true 时表示可选的「最大页码」上限（含），{@code null} 表示一直翻到接口无数据或达到站点总数。
     */
    private Integer npcPageEnd;
    private Integer npcPageSize;
    /**
     * 为 true 时按检索条件连续请求列表第 2、3…页直至无数据（与 flk.npc.gov.cn/search 检索页翻页一致）；为 false 时仅拉取 {@code npcPageStart}～{@code npcPageEnd}。未传时使用服务端配置。
     */
    private Boolean npcFetchAllListPages;
    /**
     * 为 true 时（且未开分桶）：列表按页拉取，直到已处理 {@link #npcMaxLaws} 条「需抓详情」的法规（库中已存在则跳过且不计入该上限）；适合实时补抓。与 {@link #npcFetchAllListPages} 同时 true 时以前者为准。
     */
    private Boolean npcStreamUntilDetailQuota;
    /** 单次同步最多处理的「部」法规数量，防止一次请求过大。 */
    private Integer npcMaxLaws;
    /**
     * 为 true 时忽略「数据库已有则跳过」策略，强制重新抓取（NPC 与 URL 同步均适用）。
     */
    private Boolean forceResync;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public List<String> getUrls() {
        return urls;
    }

    public void setUrls(List<String> urls) {
        this.urls = urls;
    }

    public String getNpcType() {
        return npcType;
    }

    public void setNpcType(String npcType) {
        this.npcType = npcType;
    }

    public List<String> getNpcCategoryLabels() {
        return npcCategoryLabels;
    }

    public void setNpcCategoryLabels(List<String> npcCategoryLabels) {
        this.npcCategoryLabels = npcCategoryLabels;
    }

    public String getNpcFlfgScope() {
        return npcFlfgScope;
    }

    public void setNpcFlfgScope(String npcFlfgScope) {
        this.npcFlfgScope = npcFlfgScope;
    }

    public Boolean getNpcIterateCatalogBuckets() {
        return npcIterateCatalogBuckets;
    }

    public void setNpcIterateCatalogBuckets(Boolean npcIterateCatalogBuckets) {
        this.npcIterateCatalogBuckets = npcIterateCatalogBuckets;
    }

    public String getNpcLocalRegulationsPolicy() {
        return npcLocalRegulationsPolicy;
    }

    public void setNpcLocalRegulationsPolicy(String npcLocalRegulationsPolicy) {
        this.npcLocalRegulationsPolicy = npcLocalRegulationsPolicy;
    }

    public Boolean getNpcLawModuleOnly() {
        return npcLawModuleOnly;
    }

    public void setNpcLawModuleOnly(Boolean npcLawModuleOnly) {
        this.npcLawModuleOnly = npcLawModuleOnly;
    }

    public String getNpcSearchContent() {
        return npcSearchContent;
    }

    public void setNpcSearchContent(String npcSearchContent) {
        this.npcSearchContent = npcSearchContent;
    }

    public Integer getNpcSearchRange() {
        return npcSearchRange;
    }

    public void setNpcSearchRange(Integer npcSearchRange) {
        this.npcSearchRange = npcSearchRange;
    }

    public Integer getNpcSearchType() {
        return npcSearchType;
    }

    public void setNpcSearchType(Integer npcSearchType) {
        this.npcSearchType = npcSearchType;
    }

    public List<Integer> getNpcStatusCodes() {
        return npcStatusCodes;
    }

    public void setNpcStatusCodes(List<Integer> npcStatusCodes) {
        this.npcStatusCodes = npcStatusCodes;
    }

    public Integer getNpcPageStart() {
        return npcPageStart;
    }

    public void setNpcPageStart(Integer npcPageStart) {
        this.npcPageStart = npcPageStart;
    }

    public Integer getNpcPageEnd() {
        return npcPageEnd;
    }

    public void setNpcPageEnd(Integer npcPageEnd) {
        this.npcPageEnd = npcPageEnd;
    }

    public Boolean getNpcFetchAllListPages() {
        return npcFetchAllListPages;
    }

    public void setNpcFetchAllListPages(Boolean npcFetchAllListPages) {
        this.npcFetchAllListPages = npcFetchAllListPages;
    }

    public Boolean getNpcStreamUntilDetailQuota() {
        return npcStreamUntilDetailQuota;
    }

    public void setNpcStreamUntilDetailQuota(Boolean npcStreamUntilDetailQuota) {
        this.npcStreamUntilDetailQuota = npcStreamUntilDetailQuota;
    }

    public Integer getNpcPageSize() {
        return npcPageSize;
    }

    public void setNpcPageSize(Integer npcPageSize) {
        this.npcPageSize = npcPageSize;
    }

    public Integer getNpcMaxLaws() {
        return npcMaxLaws;
    }

    public void setNpcMaxLaws(Integer npcMaxLaws) {
        this.npcMaxLaws = npcMaxLaws;
    }

    public Boolean getForceResync() {
        return forceResync;
    }

    public void setForceResync(Boolean forceResync) {
        this.forceResync = forceResync;
    }
}
