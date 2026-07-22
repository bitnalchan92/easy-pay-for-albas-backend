package com.albapay.backend.account;

import com.albapay.backend.toss.SessionCookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * POST /account/withdraw. 정책/DB 로직은 전부 {@link AccountWithdrawalService}에 있고, 컨트롤러는
 * 계약(typed body → 성공 시 clear cookie + {ok:true})만 담당한다.
 */
@RestController
@RequestMapping("/account")
@RequiredArgsConstructor
public class AccountWithdrawalController {

    private final AccountWithdrawalService accountWithdrawalService;

    @PostMapping("/withdraw")
    public Map<String, Object> withdraw(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        accountWithdrawalService.withdraw(WithdrawRequest.from(body));
        response.addHeader("Set-Cookie", SessionCookie.buildClearCookieHeader());
        return Map.of("ok", true);
    }
}
