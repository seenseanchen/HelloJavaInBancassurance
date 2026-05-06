package com.sean.bancassurance.common.exception;

/**
 * 找不到資源的業務例外。
 *
 * 為什麼自訂例外，而不是直接 throw new RuntimeException？(CLAUDE.md 守則)
 *  - 可在 ControllerAdvice 中精準對應到 HTTP 404
 *  - 攜帶結構化資訊 (resourceType + identifier)，方便日誌與 client 排查
 *  - 業務語意清楚：呼叫端一看 throws ResourceNotFoundException 就知道要處理「找不到」
 *
 * 為什麼繼承 RuntimeException 而不是 Exception (checked)？
 *  - Spring 的 @Transactional 預設只對 RuntimeException rollback
 *  - checked exception 會強迫呼叫鏈一路 throws，造成樣板程式碼與耦合
 *  - 主流框架 (Spring / Hibernate) 都用 unchecked，跟著走比較順
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String resourceType;
    private final String identifier;

    public ResourceNotFoundException(String resourceType, String identifier) {
        super("%s not found: %s".formatted(resourceType, identifier));
        this.resourceType = resourceType;
        this.identifier = identifier;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getIdentifier() {
        return identifier;
    }
}
