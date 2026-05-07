package com.sean.bancassurance.policy.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 變更受益人請求 — M5 的「主菜」。集合替換 (replace) 策略。
 *
 * Bean Validation 在 nested collection 的眉角：
 *
 *  @NotEmpty   ：list 不能是 null，也不能是空 list
 *               為什麼 list 不能是空？變更後保單必須至少留一位受益人，
 *               「想刪光受益人」屬於「保單終止」流程，不在本 endpoint 範圍。
 *
 *  @Size(max=10) ：實務上一張保單最多 10 個受益人 — 防呆，避免 client 送 10000 筆 OOM
 *
 *  @Valid ★ 重要！
 *    沒加 @Valid，list element (BeneficiaryUpsert) 內的 @NotBlank / @Min 等 annotation
 *    都不會被檢查 — 只會檢查 list 本身的 @NotEmpty / @Size。
 *    @Valid 是「遞迴下去」的開關。
 *
 *  (面試題 / 中級)：「@Valid 跟 @Validated 差在哪？」
 *    答：
 *      - @Valid 是 Jakarta Bean Validation 標準，可以放在欄位 / 參數 / list 元素
 *      - @Validated 是 Spring 自家的擴充，多了 group 功能 (e.g. @Validated(Update.class))
 *      - Controller 接 @RequestBody 時兩個都行；但「對 list element 遞迴」必須 @Valid
 *
 * (面試題 / 資深)：「全量替換 vs 增量 PATCH 怎麼選？」
 *  全量替換：protocol 簡單，但 client 要先 GET 再 PATCH (兩次 round trip)，
 *           且 GET 與 PATCH 之間若有人改了 → 你會把對方的變更覆蓋掉
 *           → 這就是樂觀鎖 expectedVersion 要解決的問題！
 *  增量 PATCH (JSON Patch RFC 6902)：[{"op":"replace","path":"/beneficiaries/0/percent","value":50}]
 *           只送 diff，protocol 複雜但 client 負擔小、衝突面小
 *  M5 採全量替換，配合樂觀鎖。
 */
public record ChangeBeneficiariesRequest(

        @NotNull(message = "expectedVersion is required")
        @Min(value = 0, message = "expectedVersion must be >= 0")
        Long expectedVersion,

        @NotEmpty(message = "beneficiaries cannot be empty (at least one required)")
        @Size(max = 10, message = "beneficiaries cannot exceed 10")
        @Valid   // ★ 沒加這個，element 內的驗證不會跑！
        List<BeneficiaryUpsert> beneficiaries,

        @Size(max = 500, message = "reason must be <= 500 characters")
        String reason
) {
}
