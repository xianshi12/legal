package com.fatongai.legalassistant.document.service;

import com.fatongai.legalassistant.document.dto.DocumentTemplateView;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DocumentTemplateService {
    private final Map<String, DocumentTemplateView> templates = new LinkedHashMap<>();

    public DocumentTemplateService() {
        register(new DocumentTemplateView(
                "iou", "借条", "基础文书", "民间借贷",
                List.of("出借人", "借款人", "金额", "借款期限", "利率", "还款方式", "担保信息"),
                List.of("写明大小写金额、交付方式和还款期限", "利息不得超过法律保护上限", "建议补充逾期利息、律师费、管辖法院和送达地址"),
                true));
        register(new DocumentTemplateView(
                "debt-note", "欠条", "基础文书", "欠款确认",
                List.of("债权人", "债务人", "欠款金额", "欠款原因", "还款日期", "违约责任"),
                List.of("说明欠款形成原因，避免被认定基础关系不明", "约定明确的还款日期和违约责任", "保留对账、转账、聊天记录等证据"),
                true));
        register(new DocumentTemplateView(
                "receipt", "收条", "基础文书", "款项收取",
                List.of("收款人", "付款人", "金额", "款项用途", "收款方式", "日期"),
                List.of("写清收到的是现金、转账还是其他财物", "注明款项用途，避免与其他债务混同", "收款人签名、日期应完整"),
                false));
        register(new DocumentTemplateView(
                "authorization", "委托书", "基础文书", "授权代理",
                List.of("委托人", "受托人", "授权事项", "授权期限", "转委托权限", "签署日期"),
                List.of("授权事项要具体，避免概括授权引发争议", "写明有效期限和是否允许转委托", "涉及重大处分权益时建议单独列明"),
                true));
        register(new DocumentTemplateView(
                "complaint", "民事起诉状", "专业文书", "合同纠纷",
                List.of("原告信息", "被告信息", "案由", "诉讼请求", "事实与理由", "证据目录", "管辖法院"),
                List.of("诉讼请求应可执行、可量化", "事实理由按时间线陈述并对应证据", "管辖法院需结合合同约定、被告住所地或履行地判断"),
                true));
        register(new DocumentTemplateView(
                "answer", "民事答辩状", "专业文书", "应诉答辩",
                List.of("答辩人信息", "被答辩人信息", "案号", "答辩意见", "事实与理由", "证据目录", "法院名称"),
                List.of("先回应对方诉讼请求，再展开事实和法律理由", "对不认可事实逐项说明并提交反证", "注意举证期限和开庭时间"),
                true));
        register(new DocumentTemplateView(
                "lawyer-letter", "律师函", "专业文书", "催告维权",
                List.of("委托人", "相对方", "事实经过", "违约或侵权行为", "要求事项", "履行期限", "联系方式"),
                List.of("要求事项应明确、合法、可履行", "避免使用侮辱性或过度威胁表述", "保留送达凭证"),
                true));
        register(new DocumentTemplateView(
                "divorce-agreement", "离婚协议书", "专业文书", "婚姻家事",
                List.of("男方信息", "女方信息", "子女抚养", "财产分割", "债务处理", "探望安排", "违约责任"),
                List.of("子女抚养、探望、抚养费应具体", "房产、车辆、存款、债务需逐项列明", "协议以办理离婚登记为生效关键节点"),
                true));
        register(new DocumentTemplateView(
                "labor-contract", "劳动合同", "合同文书", "劳动用工",
                List.of("用人单位", "劳动者", "岗位", "合同期限", "工作地点", "薪酬", "工时休假", "社保福利"),
                List.of("试用期、岗位和薪酬结构必须清晰", "竞业限制、保密条款需匹配补偿安排", "避免免除用人单位法定义务的条款"),
                true));
    }

    public List<DocumentTemplateView> list() {
        return templates.values().stream().toList();
    }

    public DocumentTemplateView require(String type) {
        DocumentTemplateView template = templates.get(type);
        if (template == null) {
            throw new IllegalArgumentException("暂不支持该文书类型");
        }
        return template;
    }

    private void register(DocumentTemplateView template) {
        templates.put(template.type(), template);
    }
}
