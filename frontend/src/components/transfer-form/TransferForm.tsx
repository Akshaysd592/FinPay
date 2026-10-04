"use client";

import { useCallback, useState, type FormEvent } from "react";
import { useIntl } from "react-intl";
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField, Typography } from "@mui/material";
import { parseRupees, transferFormSchema } from "@/lib/PaymentUtils";
import type { PaymentRequest } from "@/lib/ApiSchemas";

interface ITransferFormProps { walletId: string; balanceMinor: number; disabled: boolean; onSubmit: (request: PaymentRequest) => Promise<void> }

export default function TransferForm({ walletId, balanceMinor, disabled, onSubmit }: ITransferFormProps) {
  const intl = useIntl();
  const [form, setForm] = useState({ target: "", amount: "", description: "", purpose: "TRANSFER" });
  const [error, setError] = useState<string | null>(null);
  const [review, setReview] = useState<PaymentRequest | null>(null);
  const label = (id: string) => intl.formatMessage({ id });
  const change = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    setForm((previous) => ({ ...previous, [event.target.name]: event.target.value }));
    setError(null);
  }, []);
  const prepare = useCallback((event: FormEvent) => {
    event.preventDefault();
    const parsed = transferFormSchema.safeParse(form);
    if (!parsed.success) { setError("invalidForm"); return; }
    if (parsed.data.target === walletId) { setError("differentWallets"); return; }
    
    const amountMinor = parseRupees(parsed.data.amount);
    if (amountMinor > balanceMinor) { setError("INSUFFICIENT_FUNDS"); return; }
    
    setReview({ sourceWalletId: walletId, targetWalletId: parsed.data.target, amountMinor,
      currency: "INR", description: parsed.data.description, purpose: parsed.data.purpose });
  }, [form, walletId, balanceMinor]);
  const cancel = useCallback(() => setReview(null), []);
  const confirm = useCallback(async () => {
    if (!review) return;
    const request = review;
    setReview(null);
    await onSubmit(request);
  }, [onSubmit, review]);

  return <>
    <Stack component="form" spacing={2} onSubmit={prepare}>
      <Typography color="text.secondary" variant="body2">{label("transferHint")}</Typography>
      {error && <Alert severity="error">{label(error)}</Alert>}
      <TextField name="target" label={label("recipient")} value={form.target} onChange={change} required disabled={disabled} autoComplete="off" />
      <TextField name="amount" label={label("amount")} value={form.amount} onChange={change} required disabled={disabled}
        slotProps={{ htmlInput: { inputMode: "decimal", maxLength: 10 } }} />
      <TextField name="description" label={label("description")} value={form.description} onChange={change} required disabled={disabled}
        slotProps={{ htmlInput: { maxLength: 200 } }} />
      <TextField select name="purpose" label={label("purpose")} value={form.purpose} onChange={change} disabled={disabled}>
        <MenuItem value="TRANSFER">{label("transfer")}</MenuItem><MenuItem value="MERCHANT">{label("merchant")}</MenuItem>
      </TextField>
      <Button type="submit" variant="contained" size="large" disabled={disabled}>{label("review")}</Button>
    </Stack>
    <Dialog open={Boolean(review)} onClose={cancel} fullWidth maxWidth="sm">
      <DialogTitle>{label("confirmTitle")}</DialogTitle>
      <DialogContent><Stack spacing={2}>
        <Typography variant="h4">{intl.formatNumber((review?.amountMinor ?? 0) / 100, { style: "currency", currency: "INR" })}</Typography>
        <Typography sx={{ overflowWrap: "anywhere" }}>{label("recipient")}: {review?.targetWalletId}</Typography>
        <Typography>{review?.description}</Typography><Alert severity="warning">{label("reviewWarning")}</Alert>
      </Stack></DialogContent>
      <DialogActions><Button onClick={cancel}>{label("cancel")}</Button><Button variant="contained" onClick={confirm} disabled={disabled}>{label("confirm")}</Button></DialogActions>
    </Dialog>
  </>;
}
