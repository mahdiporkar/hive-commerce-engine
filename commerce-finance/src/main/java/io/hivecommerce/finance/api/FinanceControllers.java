package io.hivecommerce.finance.api;

import io.hivecommerce.finance.application.FinanceService;
import io.hivecommerce.finance.application.FinanceService.PayoutView;
import io.hivecommerce.finance.application.FinanceService.RuleCommand;
import io.hivecommerce.finance.application.FinanceService.RuleView;
import io.hivecommerce.finance.application.FinanceService.SellerFinance;
import io.hivecommerce.finance.application.FinanceService.SettlementView;
import io.hivecommerce.finance.application.FinanceService.TrialBalance;
import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.web.HiveOperation;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

final class FinanceControllers {
  private FinanceControllers() {}

  record SettlementBody(UUID sellerId) {}

  record PayoutBody(UUID settlementId) {}

  record NoteBody(String note) {}

  /** Operator finance (AUTHENTICATED: commerce.api.finance:view / :settle / :approve_payout). */
  @RestController
  @RequestMapping("/v1/admin/finance")
  static class Admin {
    private final FinanceService finance;

    Admin(FinanceService finance) {
      this.finance = finance;
    }

    @PostMapping("/commission-rules")
    @ResponseStatus(HttpStatus.CREATED)
    @HiveOperation("admin-commission-rule-create")
    RuleView createRule(UserContext user, @RequestBody RuleCommand body) { return finance.createRule(user, body); }

    @GetMapping("/commission-rules")
    @HiveOperation("admin-commission-rules")
    List<RuleView> rules(UserContext user) { return finance.rules(user); }

    @GetMapping("/balances")
    @HiveOperation("admin-finance-balances")
    TrialBalance balances(UserContext user, @RequestParam String currency) { return finance.trialBalance(user, currency); }

    @PostMapping("/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    @HiveOperation("admin-settlement-close")
    SettlementView close(UserContext user, @RequestBody SettlementBody body) { return finance.closeSettlement(user, body.sellerId()); }

    @GetMapping("/settlements")
    @HiveOperation("admin-settlements")
    List<SettlementView> settlements(UserContext user, @RequestParam(required = false) UUID sellerId) { return finance.settlements(user, sellerId); }

    @GetMapping("/payouts")
    @HiveOperation("admin-payouts")
    List<PayoutView> payouts(UserContext user, @RequestParam(required = false) UUID sellerId) { return finance.payouts(user, sellerId); }

    @PostMapping("/payouts/{payoutId}/approve")
    @HiveOperation("admin-payout-approve")
    PayoutView approve(UserContext user, @PathVariable UUID payoutId) { return finance.approvePayout(user, payoutId); }

    @PostMapping("/payouts/{payoutId}/reject")
    @HiveOperation("admin-payout-reject")
    PayoutView reject(UserContext user, @PathVariable UUID payoutId, @RequestBody NoteBody body) { return finance.rejectPayout(user, payoutId, body.note()); }

    @PostMapping("/payouts/{payoutId}/paid")
    @HiveOperation("admin-payout-paid")
    PayoutView paid(UserContext user, @PathVariable UUID payoutId, @RequestBody NoteBody body) { return finance.markPayoutPaid(user, payoutId, body.note()); }
  }

  /** Seller workspace (HYBRID; Commerce checks finance.view on that seller). */
  @RestController
  @RequestMapping("/v1/seller/sellers/{sellerId}")
  static class Seller {
    private final FinanceService finance;

    Seller(FinanceService finance) {
      this.finance = finance;
    }

    @GetMapping("/finance")
    @HiveOperation("seller-finance")
    SellerFinance overview(UserContext user, @PathVariable UUID sellerId, @RequestParam String currency) { return finance.sellerFinance(user, sellerId, currency); }

    @PostMapping("/payouts")
    @ResponseStatus(HttpStatus.CREATED)
    @HiveOperation("seller-payout-request")
    PayoutView requestPayout(UserContext user, @PathVariable UUID sellerId, @RequestBody PayoutBody body) { return finance.requestPayout(user, sellerId, body.settlementId()); }
  }
}
