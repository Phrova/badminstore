package com.example.demo.shipping.service.impl;

import com.example.demo.shipping.config.GhnProperties;
import com.example.demo.shipping.dto.GhnApiException;
import com.example.demo.shipping.dto.GhnResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tầng HTTP THUẦN của GHN: chỉ lo dựng request, gắn header Token/ShopId, đọc envelope
 * {@link GhnResponse} và bọc MỌI lỗi HTTP/JSON/mạng thành {@link GhnApiException}.
 *
 * Không chứa quy tắc nghiệp vụ (trạng thái đơn, COD, chọn đơn) và không bao giờ ghi token
 * vào log/thông báo. {@link GhnClient} dùng lớp này rồi mới thêm cache/mock/nghiệp vụ.
 */
class GhnApiClient {

    private static final Logger log = LoggerFactory.getLogger(GhnApiClient.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GhnProperties props;
    private final RestClient restClient;

    GhnApiClient(GhnProperties props, RestClient restClient) {
        this.props = props;
        this.restClient = restClient;
    }

    /** Gọi GET và trả nguyên envelope để nơi gọi tự quyết định data null có hợp lệ không. */
    <T> GhnResponse<T> get(String path, Map<String, String> query,
                           ParameterizedTypeReference<GhnResponse<T>> type, String action) {
        try {
            return restClient.get()
                    .uri(uri(path, query))
                    .headers(this::authHeaders)
                    .retrieve()
                    .body(type);
        } catch (RestClientException ex) {
            throw translate(ex, action);
        }
    }

    /** Gọi POST JSON và trả nguyên envelope. */
    <T> GhnResponse<T> post(String path, Object body,
                            ParameterizedTypeReference<GhnResponse<T>> type, String action) {
        try {
            return restClient.post()
                    .uri(uri(path, Map.of()))
                    .headers(this::authHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(type);
        } catch (RestClientException ex) {
            throw translate(ex, action);
        }
    }

    /** Gọi GET và bắt buộc có data. */
    <T> T getData(String path, Map<String, String> query,
                  ParameterizedTypeReference<GhnResponse<T>> type, String action) {
        return requireData(get(path, query, type, action), action);
    }

    /** Gọi POST và bắt buộc có data. */
    <T> T postData(String path, Object body,
                   ParameterizedTypeReference<GhnResponse<T>> type, String action) {
        return requireData(post(path, body, type, action), action);
    }

    /** Kiểm tra envelope thành công, trả data (có thể null với một số endpoint đặc biệt). */
    <T> T require(GhnResponse<T> response, String action) {
        if (response == null) {
            throw new GhnApiException("GHN không trả dữ liệu khi " + action + ".");
        }
        if (!response.success()) {
            throw new GhnApiException("GHN từ chối yêu cầu " + action + ": " + response.errorMessage(),
                    null, response.code(), response.codeMessage(), retryableCode(response.code()), null);
        }
        return response.data();
    }

    /** Như {@link #require} nhưng bắt buộc phải có data. */
    <T> T requireData(GhnResponse<T> response, String action) {
        T data = require(response, action);
        if (data == null) {
            throw new GhnApiException("GHN không trả dữ liệu khi " + action + ".");
        }
        return data;
    }

    private String uri(String path, Map<String, String> query) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath(path);
        if (query != null) {
            query.forEach((name, value) -> {
                if (StringUtils.hasText(value)) {
                    builder.queryParam(name, value);
                }
            });
        }
        return builder.build().encode(StandardCharsets.UTF_8).toUriString();
    }

    private void authHeaders(HttpHeaders headers) {
        if (StringUtils.hasText(props.getToken())) {
            headers.set("Token", props.getToken());
        }
        if (props.getShopId() != null) {
            headers.set("ShopId", String.valueOf(props.getShopId()));
        }
    }

    private GhnApiException translate(RestClientException ex, String action) {
        if (ex instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            ErrorInfo error = parseError(response.getResponseBodyAsString());
            boolean retryable = status == 408 || status == 429 || status >= 500;
            String detail = error.message() == null ? "không rõ lý do" : error.message();
            log.warn("GHN HTTP {} khi {}: code={} message={}", status, action, error.code(), detail);
            return new GhnApiException("GHN từ chối " + action + " (HTTP " + status + "): " + detail,
                    status, error.code(), error.codeMessage(), retryable, ex);
        }
        boolean retryable = isRetryableTransportError(ex);
        log.warn("GHN không kết nối được khi {}: {}", action, ex.getMessage());
        return new GhnApiException("Không gọi được API GHN (" + action
                + "). Vui lòng kiểm tra kết nối mạng, token và shopId.",
                null, null, null, retryable, ex);
    }

    /** Đọc code/code_message/message từ body lỗi; không đưa toàn bộ body (có thể chứa PII) vào message. */
    private ErrorInfo parseError(String body) {
        if (!StringUtils.hasText(body)) {
            return new ErrorInfo(null, null, null);
        }
        try {
            JsonNode node = JSON.readTree(body);
            Integer code = node.has("code") && node.get("code").canConvertToInt() ? node.get("code").asInt() : null;
            String codeMessage = text(node, "code_message");
            String message = text(node, "message");
            return new ErrorInfo(code, codeMessage, message == null ? codeMessage : message);
        } catch (Exception ignored) {
            return new ErrorInfo(null, null, "phản hồi không đúng định dạng JSON");
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private boolean isRetryableTransportError(RestClientException ex) {
        if (ex instanceof ResourceAccessException) {
            Throwable cause = ex.getCause();
            while (cause != null) {
                if (cause instanceof java.net.SocketTimeoutException || cause instanceof HttpTimeoutException) {
                    return true;
                }
                cause = cause.getCause();
            }
            return true;
        }
        return false;
    }

    private boolean retryableCode(int code) {
        return code == 408 || code == 429 || code >= 500;
    }

    private record ErrorInfo(Integer code, String codeMessage, String message) {
    }
}
