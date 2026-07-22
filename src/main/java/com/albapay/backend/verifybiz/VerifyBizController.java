package com.albapay.backend.verifybiz;

import com.albapay.backend.common.exception.BusinessException;
import com.albapay.backend.common.exception.ErrorCode;
import com.albapay.backend.config.AlbapayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequiredArgsConstructor
public class VerifyBizController {

    private static final Pattern B_NO_PATTERN = Pattern.compile("^\\d{10}$");

    private final AlbapayProperties props;
    private final RestClient ntsRestClient = RestClient.create();

    @PostMapping(value = "/verify-biz")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, Object> body) {
        Object bNoValue = body.get("b_no");
        String bNo = bNoValue == null ? null : String.valueOf(bNoValue);
        if (bNo == null || !B_NO_PATTERN.matcher(bNo).matches()) {
            throw new BusinessException(ErrorCode.INVALID_BIZ_NUMBER);
        }

        String url = "https://api.odcloud.kr/api/nts-businessman/v1/status?serviceKey="
                + props.getNts().getApiKey();

        // serviceKey is already percent-encoded (공공데이터포털 Encoding key). RestClient's
        // uri(String) treats the argument as a URI template and re-encodes it, which would
        // double-encode the key (e.g. %2B -> %252B) and get rejected by odcloud as an
        // unregistered key. URI.create() bypasses template re-encoding.
        NtsResponse response = ntsRestClient.post()
                .uri(URI.create(url))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("b_no", List.of(bNo)))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    throw new BusinessException(ErrorCode.NTS_API_ERROR);
                })
                .body(NtsResponse.class);

        NtsBusinessStatus result = (response == null || response.data() == null || response.data().isEmpty())
                ? null
                : response.data().get(0);

        if (result == null) {
            throw new BusinessException(ErrorCode.BIZ_NOT_FOUND);
        }

        Map<String, Object> responseBody = Map.of(
                "b_stt", result.b_stt(),
                "b_stt_cd", result.b_stt_cd(),
                "isActive", "01".equals(result.b_stt_cd())
        );
        return ResponseEntity.ok(responseBody);
    }

    private record NtsResponse(String status_code, List<NtsBusinessStatus> data) {
    }

    private record NtsBusinessStatus(String b_no, String b_stt, String b_stt_cd) {
    }
}
