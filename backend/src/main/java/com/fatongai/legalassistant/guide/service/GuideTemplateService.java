package com.fatongai.legalassistant.guide.service;

import com.fatongai.legalassistant.guide.dto.GuideResponse;
import com.fatongai.legalassistant.guide.dto.GuideStep;
import com.fatongai.legalassistant.guide.dto.GuideTemplateView;
import com.fatongai.legalassistant.guide.dto.LimitationResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GuideTemplateService {
    private final Map<String, GuideTemplateView> templates = new LinkedHashMap<>();

    public GuideTemplateService() {
        register(new GuideTemplateView("labor-arbitration", "劳动仲裁流程", "劳动纠纷", "劳动仲裁", 1, "工资、辞退、补偿金、工伤待遇等劳动争议。"));
        register(new GuideTemplateView("civil-lawsuit", "民事起诉流程", "民事诉讼", "一般民事", 3, "合同、侵权、借贷、租赁等一般民事纠纷。"));
        register(new GuideTemplateView("loan-collection", "民间借贷维权流程", "民间借贷", "民间借贷", 3, "借款本金、利息、逾期还款、担保责任。"));
        register(new GuideTemplateView("lease-deposit", "租房押金维权流程", "租赁纠纷", "租赁押金", 3, "房东提前解约、押金不退、房屋损坏扣款。"));
        register(new GuideTemplateView("work-injury", "工伤认定流程", "工伤维权", "工伤认定", 1, "事故伤害、职业病、工伤认定和待遇申请。"));
        register(new GuideTemplateView("divorce-lawsuit", "离婚诉讼流程", "婚姻家事", "一般民事", 3, "离婚、抚养权、财产分割、债务处理。"));
        register(new GuideTemplateView("consumer-complaint", "消费维权流程", "消费纠纷", "一般民事", 3, "退款、欺诈、虚假宣传、质量问题。"));
        register(new GuideTemplateView("contract-termination", "合同解除维权流程", "合同纠纷", "一般民事", 3, "解除合同、违约责任、赔偿损失。"));
    }

    public List<GuideTemplateView> list() {
        return templates.values().stream().toList();
    }

    public GuideTemplateView get(String type) {
        return templates.getOrDefault(type, templates.get("labor-arbitration"));
    }

    public GuideResponse build(String flowType, LimitationResult limitation) {
        GuideTemplateView template = get(flowType);
        return switch (template.type()) {
            case "civil-lawsuit" -> civil(template, limitation);
            case "loan-collection" -> loan(template, limitation);
            case "lease-deposit" -> lease(template, limitation);
            case "work-injury" -> injury(template, limitation);
            case "divorce-lawsuit" -> divorce(template, limitation);
            case "consumer-complaint" -> consumer(template, limitation);
            case "contract-termination" -> contract(template, limitation);
            default -> labor(template, limitation);
        };
    }

    private GuideResponse labor(GuideTemplateView t, LimitationResult limitation) {
        return response(t, "劳动仲裁通常是劳动争议进入诉讼前的前置程序，应先确定管辖仲裁委、准备申请书和证据。",
                List.of(
                        new GuideStep("确认管辖与请求", "准备阶段", "确认劳动合同履行地或用人单位所在地仲裁委，明确工资、补偿金、赔偿金等请求金额。", true, false),
                        new GuideStep("提交申请", "T-0 日", "提交仲裁申请书、身份材料、企业登记信息和证据清单。", false, false),
                        new GuideStep("审查受理", "T+5 日内", "仲裁委通常在收到申请后五日内决定是否受理。", false, false),
                        new GuideStep("开庭审理", "T+30 日左右", "完成举证、质证、调解和辩论，重点说明劳动关系与请求依据。", false, false),
                        new GuideStep("出具裁决", "通常45日内", "仲裁庭一般在受理后45日内结案，复杂案件可延长。", false, true)),
                List.of("劳动仲裁申请书", "身份证复印件", "企业登记信息", "劳动合同、工牌、社保记录", "工资流水、考勤记录、解除通知", "证据清单及附件"),
                List.of("劳动争议仲裁时效通常为一年", "欠薪争议在劳动关系存续期间主张一般不受一年限制，但终止后应及时主张", "请求金额要可计算并与证据对应"),
                List.of("劳动合同", "工资流水", "考勤记录", "社保缴纳记录", "解除或辞退通知", "聊天记录"));
    }

    private GuideResponse civil(GuideTemplateView t, LimitationResult limitation) {
        return response(t, "民事起诉应先确定被告、管辖法院、诉讼请求和证据目录，再提交起诉材料并等待立案审查。",
                List.of(
                        new GuideStep("整理请求和证据", "起诉前", "明确要求付款、返还、赔偿、解除合同等具体请求，并按时间线整理证据。", true, false),
                        new GuideStep("确定管辖法院", "起诉前", "结合被告住所地、合同履行地、约定管辖等判断法院。", false, false),
                        new GuideStep("提交立案", "T-0 日", "通过法院窗口或线上平台提交起诉状、证据和主体材料。", false, false),
                        new GuideStep("缴费与送达", "T+7 日左右", "收到缴费通知后按期缴费，法院向被告送达材料。", false, false),
                        new GuideStep("开庭与判决", "视排期而定", "参加庭审，围绕争议焦点举证质证，等待判决或调解。", false, true)),
                List.of("民事起诉状", "原被告主体身份材料", "证据目录", "合同、转账、聊天记录等证据", "授权委托材料（如有）"),
                List.of("一般民事诉讼时效为三年", "诉讼请求应具体、可执行", "注意保全财产和证据的必要性"),
                List.of("合同或协议", "付款凭证", "沟通记录", "催告记录", "损失证明"));
    }

    private GuideResponse loan(GuideTemplateView t, LimitationResult limitation) {
        return response(t, "民间借贷维权应重点证明借贷合意、款项交付、到期未还和利息约定合法。",
                List.of(
                        new GuideStep("核对借贷证据", "起诉前", "整理借条、转账记录、聊天记录、催款记录，确认本金和利息。", true, false),
                        new GuideStep("发送催告", "起诉前", "通过微信、短信、律师函等方式催告还款并保留送达证据。", false, false),
                        new GuideStep("准备起诉材料", "T-0 日", "提交起诉状、证据目录、主体身份材料和利息计算表。", false, false),
                        new GuideStep("申请财产保全", "必要时", "如担心对方转移财产，可评估是否申请诉前或诉中保全。", false, true),
                        new GuideStep("庭审和执行", "判决后", "胜诉后对方不履行，可申请强制执行。", false, false)),
                List.of("借条或借款合同", "银行/微信/支付宝转账凭证", "催款聊天记录", "对方身份信息", "利息计算表"),
                List.of("民间借贷通常适用三年诉讼时效", "利息不得超过法律保护上限", "现金交付需补强证据"),
                List.of("借条", "转账凭证", "聊天记录", "催告记录", "还款记录"));
    }

    private GuideResponse lease(GuideTemplateView t, LimitationResult limitation) {
        return response(t, "租房押金维权应围绕合同解除原因、押金性质、实际损失和房屋交接证据展开。",
                List.of(
                        new GuideStep("固定解约事实", "立即", "保存房东提前解约、不退押金、扣款理由等聊天记录。", true, false),
                        new GuideStep("完成房屋交接", "退租时", "拍摄房屋状态、水电表、钥匙交还和物品清单。", false, false),
                        new GuideStep("书面催告退还", "交接后", "发送退押金催告，写明金额、期限和收款账户。", false, false),
                        new GuideStep("投诉或调解", "可选", "可向住建、消协、街道调解组织或平台投诉。", false, false),
                        new GuideStep("起诉维权", "协商无果", "准备起诉状和证据，主张退还押金、剩余租金及违约责任。", false, true)),
                List.of("租赁合同", "押金和租金转账凭证", "聊天记录", "房屋交接照片/视频", "催告函及送达记录"),
                List.of("押金不能无条件没收，应与实际损失相匹配", "注意合同中的提前解约和违约金条款", "房屋状态证据越完整，押金争议越容易判断"),
                List.of("租赁合同", "押金转账凭证", "解约聊天记录", "交接照片", "催告记录"));
    }

    private GuideResponse injury(GuideTemplateView t, LimitationResult limitation) {
        return response(t, "工伤认定应尽快由单位或劳动者向社保行政部门申请，并同步准备劳动关系和事故证据。",
                List.of(
                        new GuideStep("就医和固定事故", "事故后立即", "及时就医，保存病历、诊断证明、事故现场材料和证人信息。", true, false),
                        new GuideStep("单位申请工伤认定", "30日内", "单位应在事故发生或诊断职业病之日起30日内申请。", false, true),
                        new GuideStep("个人申请工伤认定", "1年内", "单位未申请的，劳动者或近亲属可在1年内申请。", false, true),
                        new GuideStep("劳动能力鉴定", "伤情稳定后", "经治疗伤情相对稳定且存在残疾、影响劳动能力的，可申请鉴定。", false, false),
                        new GuideStep("待遇申领或仲裁", "鉴定后", "根据工伤认定和鉴定结论主张医疗费、停工留薪、伤残待遇等。", false, false)),
                List.of("工伤认定申请表", "劳动关系证明", "医疗诊断证明", "事故经过说明", "证人证言", "单位登记信息"),
                List.of("个人申请工伤认定通常应在1年内提出", "先确认劳动关系，必要时先申请劳动仲裁确认", "保留原始病历和缴费票据"),
                List.of("劳动合同或工资流水", "病历和诊断证明", "事故现场照片", "证人信息", "报警或事故记录"));
    }

    private GuideResponse divorce(GuideTemplateView t, LimitationResult limitation) {
        return response(t, "离婚诉讼需准备身份关系、感情破裂、子女抚养、财产债务等证据，并明确诉讼请求。",
                List.of(
                        new GuideStep("梳理诉求", "起诉前", "明确是否请求离婚、子女抚养、抚养费、财产分割和债务承担。", true, false),
                        new GuideStep("准备证据", "起诉前", "整理结婚证、子女出生证明、财产线索、债务材料和感情破裂证据。", false, false),
                        new GuideStep("提交起诉", "T-0 日", "向有管辖权法院提交起诉状和证据材料。", false, false),
                        new GuideStep("调解和开庭", "排期后", "法院通常先行调解，调解不成进入庭审。", false, false),
                        new GuideStep("判决或调解结案", "审理后", "法院结合感情是否破裂、子女利益和财产证据作出处理。", false, true)),
                List.of("离婚起诉状", "身份证和结婚证", "子女出生证明", "财产和债务证据", "感情破裂证据", "抚养能力证明"),
                List.of("财产线索应尽早固定，必要时申请调查令或保全", "涉及家暴应及时报警、就医并申请保护令", "子女抚养以未成年子女利益最大化为原则"),
                List.of("结婚证", "户口本或出生证明", "房产/车辆/存款材料", "债务凭证", "报警记录或就医记录"));
    }

    private GuideResponse consumer(GuideTemplateView t, LimitationResult limitation) {
        return response(t, "消费维权可先与商家协商、平台投诉或行政投诉，协商无果再考虑起诉。",
                List.of(
                        new GuideStep("固定消费证据", "立即", "保存订单、发票、付款记录、商品照片、宣传页面和沟通记录。", true, false),
                        new GuideStep("联系商家/平台", "发现问题后", "提出退款、换货、维修、赔偿等明确请求。", false, false),
                        new GuideStep("投诉调解", "协商无果", "向平台、12315、消协或市场监管部门投诉。", false, false),
                        new GuideStep("准备起诉", "调解无果", "整理起诉状、证据目录、损失计算和被告信息。", false, true)),
                List.of("订单和付款凭证", "商品/服务问题照片或检测报告", "商家宣传页面", "聊天记录", "投诉记录"),
                List.of("欺诈主张需要证明虚假告知和误导购买", "质量问题建议保留实物或检测材料", "注意七日无理由退货的适用边界"),
                List.of("订单截图", "支付凭证", "宣传页面", "聊天记录", "检测报告或照片"));
    }

    private GuideResponse contract(GuideTemplateView t, LimitationResult limitation) {
        return response(t, "合同解除维权应先判断解除依据，履行催告或通知程序，并保存送达和损失证据。",
                List.of(
                        new GuideStep("审查解除依据", "解除前", "核对合同约定解除、法定解除条件和对方违约事实。", true, false),
                        new GuideStep("发送催告或解除通知", "必要时", "通过可留痕方式送达，写明违约事实、整改期限或解除后果。", false, true),
                        new GuideStep("停止或交接履行", "解除后", "完成交接、结算、退还材料或设备，避免扩大损失。", false, false),
                        new GuideStep("主张违约责任", "协商无果", "可通过调解、仲裁或诉讼要求赔偿、返还、支付违约金。", false, false)),
                List.of("合同原件", "违约事实证据", "催告函/解除通知", "送达凭证", "损失证明", "结算清单"),
                List.of("解除通知应明确、可送达", "先履行催告义务可降低解除风险", "违约金过高或过低可能被调整"),
                List.of("合同", "聊天记录", "付款凭证", "催告通知", "损失清单"));
    }

    private GuideResponse response(GuideTemplateView t, String summary, List<GuideStep> steps, List<String> materials,
                                   List<String> attentionPoints, List<String> evidenceChecklist, LimitationResult limitation) {
        return new GuideResponse(t.type(), t.name(), t.category(), summary, steps, materials, attentionPoints,
                evidenceChecklist, limitation, false, List.of(), LocalDateTime.now());
    }

    private GuideResponse response(GuideTemplateView t, String summary, List<GuideStep> steps, List<String> materials,
                                   List<String> attentionPoints, List<String> evidenceChecklist, LimitationResult limitation, boolean aiGenerated) {
        return new GuideResponse(t.type(), t.name(), t.category(), summary, steps, materials, attentionPoints,
                evidenceChecklist, limitation, aiGenerated, List.of(), LocalDateTime.now());
    }

    private GuideResponse response(GuideTemplateView t, String summary, List<GuideStep> steps, List<String> materials,
                                   List<String> attentionPoints, List<String> evidenceChecklist) {
        return response(t, summary, steps, materials, attentionPoints, evidenceChecklist, null, false);
    }

    private void register(GuideTemplateView template) {
        templates.put(template.type(), template);
    }
}
