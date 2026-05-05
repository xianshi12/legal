package com.fatongai.legalassistant.casebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fatongai.legalassistant.casebase.dto.CaseMatchResponse;
import com.fatongai.legalassistant.casebase.dto.CaseMatchResult;
import com.fatongai.legalassistant.casebase.dto.CaseMetaResponse;
import com.fatongai.legalassistant.casebase.entity.LegalCase;
import com.fatongai.legalassistant.casebase.mapper.LegalCaseMapper;
import com.fatongai.legalassistant.law.service.QwenEmbeddingService;
import com.fatongai.legalassistant.rag.service.RagKnowledgeService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class LegalCaseService {
    private final LegalCaseMapper mapper;
    private final QwenEmbeddingService embeddingService;
    private final RagKnowledgeService ragKnowledgeService;

    public LegalCaseService(LegalCaseMapper mapper, QwenEmbeddingService embeddingService, RagKnowledgeService ragKnowledgeService) {
        this.mapper = mapper;
        this.embeddingService = embeddingService;
        this.ragKnowledgeService = ragKnowledgeService;
    }

    @PostConstruct
    @Transactional
    public void ensureSeedCases() {
        Long count = mapper.selectCount(null);
        if (count != null && count > 0) return;
        for (LegalCase legalCase : seedCases()) {
            fillCreate(legalCase);
            embed(legalCase);
            mapper.insert(legalCase);
        }
    }

    public CaseMatchResponse match(String query,
                                   String region,
                                   String yearRange,
                                   String courtLevel,
                                   Integer page,
                                   Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, 50);
        LambdaQueryWrapper<LegalCase> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(region) && !"全国".equals(region) && !"全部".equals(region)) {
            wrapper.eq(LegalCase::getRegion, region);
        }
        if (StringUtils.hasText(courtLevel) && !"全部".equals(courtLevel)) {
            wrapper.eq(LegalCase::getCourtLevel, courtLevel);
        }
        applyYearRange(wrapper, yearRange);
        List<LegalCase> cases = mapper.selectList(wrapper);
        List<Double> queryVector = StringUtils.hasText(query) ? embeddingService.embed(query) : List.of();

        List<ScoredCase> scored = cases.stream()
                .map(item -> new ScoredCase(item, score(item, query, queryVector)))
                .filter(item -> !StringUtils.hasText(query) || item.score() > 0.04)
                .sorted(Comparator.comparing(ScoredCase::score).reversed()
                        .thenComparing(item -> nullSafe(item.legalCase().getJudgmentDate()), Comparator.reverseOrder()))
                .toList();
        int from = Math.min((p - 1) * s, scored.size());
        int to = Math.min(from + s, scored.size());
        List<CaseMatchResult> items = scored.subList(from, to).stream()
                .map(item -> CaseMatchResult.of(item.legalCase(), item.score()))
                .toList();
        return new CaseMatchResponse(
                query == null ? "" : query,
                StringUtils.hasText(region) ? region : "全国",
                StringUtils.hasText(yearRange) ? yearRange : "全部",
                StringUtils.hasText(courtLevel) ? courtLevel : "全部",
                scored.size(),
                embeddingService.isEnabled(),
                items,
                ragKnowledgeService.referenceLines(query, "case", 4)
        );
    }

    public CaseMetaResponse meta() {
        List<LegalCase> cases = mapper.selectList(new QueryWrapper<LegalCase>().select("region", "court_level", "judgment_date"));
        return new CaseMetaResponse(
                distinct(cases.stream().map(LegalCase::getRegion).toList()),
                List.of("全部", "近一年", "近三年", "近五年", "2024年", "2023年", "2022年", "2021年及以前"),
                distinct(cases.stream().map(LegalCase::getCourtLevel).toList()),
                mapper.selectCount(null),
                embeddingService.isEnabled(),
                embeddingService.model()
        );
    }

    private double score(LegalCase legalCase, String query, List<Double> queryVector) {
        if (!StringUtils.hasText(query)) return 0.55;
        String q = normalize(query);
        String haystack = normalize(String.join(" ",
                safe(legalCase.getTitle()), safe(legalCase.getCaseType()), safe(legalCase.getCause()),
                safe(legalCase.getFacts()), safe(legalCase.getAdjudicationPoints()),
                safe(legalCase.getJudgmentResult()), safe(legalCase.getKeyEvidence())));
        double lexical = haystack.contains(q) ? 0.42 : 0;
        List<String> tokens = extractTokens(q);
        for (String token : tokens) {
            if (haystack.contains(token)) lexical += 0.06;
        }
        if (containsAny(q, "押金", "房东", "租房", "提前解约") && "租赁合同纠纷".equals(legalCase.getCause())) {
            lexical += 0.18;
        }
        if (containsAny(q, "辞退", "解除劳动合同", "补偿", "工资") && "劳动争议".equals(legalCase.getCause())) {
            lexical += 0.18;
        }
        if (containsAny(q, "借钱", "借款", "欠款", "利息") && "民间借贷纠纷".equals(legalCase.getCause())) {
            lexical += 0.18;
        }
        double vector = 0;
        if (!queryVector.isEmpty() && StringUtils.hasText(legalCase.getVectorJson())) {
            vector = embeddingService.cosine(queryVector, embeddingService.fromJson(legalCase.getVectorJson()));
        }
        return Math.min(0.99, lexical + vector * 0.62);
    }

    private List<String> extractTokens(String q) {
        List<String> out = new ArrayList<>();
        for (String token : q.split("\\s+|，|,|。|、")) {
            if (token.length() >= 2) out.add(token);
        }
        String[] hotWords = {"押金", "房东", "解约", "违约", "退款", "租金", "辞退", "补偿", "工资", "借款", "利息", "聊天记录", "转账"};
        for (String word : hotWords) {
            if (q.contains(word)) out.add(word);
        }
        return out;
    }

    private void applyYearRange(LambdaQueryWrapper<LegalCase> wrapper, String yearRange) {
        if (!StringUtils.hasText(yearRange) || "全部".equals(yearRange)) return;
        LocalDate today = LocalDate.now();
        if ("近一年".equals(yearRange)) {
            wrapper.ge(LegalCase::getJudgmentDate, today.minusYears(1));
        } else if ("近三年".equals(yearRange)) {
            wrapper.ge(LegalCase::getJudgmentDate, today.minusYears(3));
        } else if ("近五年".equals(yearRange)) {
            wrapper.ge(LegalCase::getJudgmentDate, today.minusYears(5));
        } else if (yearRange.matches("\\d{4}年")) {
            int year = Integer.parseInt(yearRange.substring(0, 4));
            wrapper.ge(LegalCase::getJudgmentDate, LocalDate.of(year, 1, 1));
            wrapper.le(LegalCase::getJudgmentDate, LocalDate.of(year, 12, 31));
        } else if ("2021年及以前".equals(yearRange)) {
            wrapper.le(LegalCase::getJudgmentDate, LocalDate.of(2021, 12, 31));
        }
    }

    private void embed(LegalCase legalCase) {
        if (!embeddingService.isEnabled()) return;
        String text = String.join("\n",
                legalCase.getTitle(), safe(legalCase.getCaseType()), safe(legalCase.getCause()),
                safe(legalCase.getFacts()), safe(legalCase.getAdjudicationPoints()),
                safe(legalCase.getJudgmentResult()), safe(legalCase.getKeyEvidence()));
        List<Double> vector = embeddingService.embed(text);
        if (!vector.isEmpty()) {
            legalCase.setVectorJson(embeddingService.toJson(vector));
            legalCase.setVectorModel(embeddingService.model());
        }
    }

    private void fillCreate(LegalCase legalCase) {
        LocalDateTime now = LocalDateTime.now();
        legalCase.setCreatedAt(now);
        legalCase.setUpdatedAt(now);
        if (!StringUtils.hasText(legalCase.getSourceName())) legalCase.setSourceName("内置指导案例库");
    }

    private List<LegalCase> seedCases() {
        List<LegalCase> list = new ArrayList<>();
        list.add(seed("CASE-LEASE-2023-BJ-001", "张某与某房屋中介公司房屋租赁合同纠纷案", "房屋租赁合同纠纷", "租赁合同纠纷", "北京",
                "北京市第一中级人民法院", "中级人民法院", "二审", LocalDate.of(2023, 6, 18),
                "出租方在无正当理由下单方解除合同，构成根本违约。承租方有权要求退还押金及剩余租金，并主张合理违约金。",
                "判决被告退还押金人民币10,000元，并支付违约金5,000元；其余诉讼请求依法驳回。",
                "《房屋租赁合同》原件、微信聊天记录（单方解约证明）、押金与租金转账凭证",
                "房东提前解除租赁合同并拒绝退还押金，租客提交租赁合同、转账凭证和聊天记录证明对方单方违约。"));
        list.add(seed("CASE-LEASE-2022-SH-002", "李某与王某商铺租赁合同纠纷案", "租赁合同纠纷", "租赁合同纠纷", "上海",
                "上海市浦东新区人民法院", "基层人民法院", "一审", LocalDate.of(2022, 9, 12),
                "承租方因经营困难解除合同虽构成违约，但出租方应配合解除防止损失扩大；押金扣除应与实际损失相匹配。",
                "确认双方合同解除；出租方可扣除相当于一个月租金的押金，超出部分应退还。",
                "《商铺租赁协议》、解除合同通知函及签收记录、租金支付流水",
                "商铺承租人提前退租，出租人主张没收全部押金，法院结合实际损失和减损义务调整扣款范围。"));
        list.add(seed("CASE-LABOR-2024-GD-003", "陈某与某科技公司劳动合同纠纷案", "劳动争议", "劳动争议", "广东",
                "广州市中级人民法院", "中级人民法院", "二审", LocalDate.of(2024, 3, 20),
                "用人单位以严重违纪解除劳动合同，应证明规章制度合法有效、已向劳动者公示并能证明违纪事实。",
                "判决公司支付违法解除劳动合同赔偿金，并补发部分绩效工资。",
                "劳动合同、员工手册签收页、考勤记录、解除通知书、工资流水",
                "公司以员工违反考勤制度为由辞退，但员工手册未完成民主程序且违纪证据不足。"));
        list.add(seed("CASE-LOAN-2023-ZJ-004", "王某与赵某民间借贷纠纷案", "民间借贷纠纷", "民间借贷纠纷", "浙江",
                "杭州市西湖区人民法院", "基层人民法院", "一审", LocalDate.of(2023, 11, 8),
                "民间借贷关系成立需结合借条、转账凭证、聊天记录等证据综合认定；超过法定保护上限的利息不予支持。",
                "判决借款人返还本金80,000元，并按同期LPR四倍以内标准支付利息。",
                "借条、银行转账凭证、微信催款记录、部分还款流水",
                "出借人持借条和转账记录起诉还款，双方对利息标准发生争议。"));
        list.add(seed("CASE-CONTRACT-2021-SC-005", "某装饰公司与刘某服务合同纠纷案", "服务合同纠纷", "合同纠纷", "四川",
                "成都市中级人民法院", "中级人民法院", "二审", LocalDate.of(2021, 5, 25),
                "服务质量不符合约定且整改后仍不能达到合同目的的，委托方可解除合同并要求退还未履行部分费用。",
                "判决解除合同，装饰公司退还部分服务费并赔偿合理损失。",
                "服务合同、验收照片、整改通知、第三方评估意见",
                "装修服务迟延且质量不合格，业主多次催告整改无果后解除合同。"));
        list.add(seed("CASE-MARRIAGE-2022-JS-006", "周某与孙某离婚后财产纠纷案", "离婚后财产纠纷", "婚姻家庭纠纷", "江苏",
                "南京市玄武区人民法院", "基层人民法院", "一审", LocalDate.of(2022, 12, 2),
                "离婚协议对共同财产分割已有明确约定的，一方无证据证明欺诈、胁迫或重大误解，通常应按协议履行。",
                "判决驳回重新分割房产的主要诉讼请求，支持部分补偿款支付请求。",
                "离婚协议、房产登记信息、转账记录、聊天记录",
                "双方离婚后就房产补偿款支付产生争议，一方请求重新分割房产。"));
        return list;
    }

    private LegalCase seed(String caseNo, String title, String caseType, String cause, String region,
                           String courtName, String courtLevel, String trialLevel, LocalDate date,
                           String points, String result, String evidence, String facts) {
        LegalCase c = new LegalCase();
        c.setCaseNo(caseNo);
        c.setTitle(title);
        c.setCaseType(caseType);
        c.setCause(cause);
        c.setRegion(region);
        c.setCourtName(courtName);
        c.setCourtLevel(courtLevel);
        c.setTrialLevel(trialLevel);
        c.setJudgmentDate(date);
        c.setAdjudicationPoints(points);
        c.setJudgmentResult(result);
        c.setKeyEvidence(evidence);
        c.setFacts(facts);
        c.setSourceName("内置指导案例库");
        c.setSourceUrl("seed://" + caseNo);
        return c;
    }

    private List<String> distinct(List<String> values) {
        Set<String> set = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) set.add(value);
        }
        return new ArrayList<>(set);
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    private LocalDate nullSafe(LocalDate date) {
        return date == null ? LocalDate.MIN : date;
    }

    private record ScoredCase(LegalCase legalCase, double score) {
    }
}
