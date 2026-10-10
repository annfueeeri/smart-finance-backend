package collector.bu.model;


/** 当前会话的 CSRF 令牌及请求头、参数名称。 */
public record CsrfResponse(String token, String headerName, String parameterName) { }
