package com.fatongai.legalassistant.guide.service;

import com.fatongai.legalassistant.guide.dto.LimitationResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class LimitationService {
    public LimitationResult calculate(String limitationType, String startDateText) {
        String type = StringUtils.hasText(limitationType) ? limitationType : "一般民事";
        LocalDate start = parseDate(startDateText);
        int years = years(type);
        LocalDate deadline = start.plusYears(years);
        long daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), deadline);
        String status = daysLeft < 0 ? "已可能超期" : daysLeft <= 30 ? "临近届满" : "时效内";
        return new LimitationResult(type, start, deadline, daysLeft, status, note(type, years), ruleSummary(type, years));
    }

    private int years(String type) {
        if (type.contains("劳动") && type.contains("仲裁")) return 1;
        if (type.contains("劳动")) return 1;
        if (type.contains("工伤")) return 1;
        return 3;
    }

    private String ruleSummary(String type, int years) {
        if (type.contains("劳动") && type.contains("仲裁")) {
            return "仲裁申请时效：1年";
        }
        if (type.contains("劳动")) {
            return "劳动争议相关：常按1年仲裁时效测算（具体以争议类型为准）";
        }
        if (type.contains("工伤")) {
            return "工伤认定个人申请：一般1年内（单位未申请的）";
        }
        if (type.contains("民间借贷")) {
            return "诉讼时效：3年";
        }
        if (type.contains("租赁") || type.contains("押金")) {
            return "诉讼时效：3年（租赁/押金等一般民事）";
        }
        return years >= 3 ? "诉讼时效：3年" : "法定期间：" + years + "年";
    }

    private String note(String type, int years) {
        if (type.contains("劳动") && type.contains("仲裁")) {
            return "劳动争议申请仲裁的时效期间通常为一年，自当事人知道或应当知道其权利被侵害之日起计算；劳动关系存续期间因拖欠劳动报酬发生争议的，劳动者申请仲裁一般不受一年限制，但劳动关系终止的，应自终止之日起一年内提出。";
        }
        if (type.contains("劳动")) {
            return "劳动争议申请仲裁的时效期间通常为一年，自知道或应当知道权利被侵害之日起计算。";
        }
        if (type.contains("工伤")) {
            return "单位通常应在事故伤害发生或被诊断、鉴定为职业病之日起30日内申请工伤认定；单位未申请的，工伤职工或其近亲属、工会组织通常可在1年内直接申请。本计算器按「个人申请1年」测算截止日，与单位30日期限不同，请结合实际角色核对。";
        }
        if (type.contains("民间借贷")) {
            return "民间借贷纠纷一般适用三年诉讼时效。约定还款日的，通常自履行期限届满之日起算；未约定还款日的，可结合催告、宽限期等事实确定起算点，具体以法院认定为准。";
        }
        if (type.contains("租赁") || type.contains("押金")) {
            return "房屋租赁、押金退还等纠纷一般适用三年诉讼时效，自知道或应当知道权利受侵害及义务人之日起算。";
        }
        return "一般民事纠纷向人民法院请求保护民事权利的诉讼时效期间通常为三年，自权利人知道或应当知道权利受到损害以及义务人之日起计算。法律另有规定的，依照其规定。";
    }

    private LocalDate parseDate(String text) {
        if (!StringUtils.hasText(text)) return LocalDate.now();
        try {
            return LocalDate.parse(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd");
        }
    }
}
