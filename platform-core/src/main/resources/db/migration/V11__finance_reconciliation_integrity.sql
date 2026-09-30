alter table reconciliation_item drop constraint reconciliation_item_result_check;
alter table reconciliation_item add constraint reconciliation_item_result_check
    check (result in ('MATCHED', 'MISSING_PLATFORM', 'MISSING_PROVIDER', 'AMOUNT_MISMATCH', 'TRANSACTION_MISMATCH'));

alter table payment_profit_sharing_detail drop constraint payment_profit_sharing_detail_amount_minor_check;
alter table payment_profit_sharing_detail add constraint payment_profit_sharing_detail_amount_minor_check
    check (amount_minor > 0 or (amount_minor = 0 and status = 'CANCELLED'));

alter table wallet_account add constraint wallet_available_balance_check
    check (frozen_minor <= balance_minor);
