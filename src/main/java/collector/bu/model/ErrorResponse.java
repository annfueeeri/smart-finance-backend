package collector.bu.model;


/** 统一错误响应，供前端根据错误码显示异常提示。 */
public record ErrorResponse(String code, String message) { }
