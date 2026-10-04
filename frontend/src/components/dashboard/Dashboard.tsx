"use client";

import { useCallback } from "react";
import { useIntl } from "react-intl";
import { Alert, Box, Button, Card, Chip, CircularProgress, Container, Divider, LinearProgress,
  Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from "@mui/material";
import { useDashboard } from "@/hooks/useDashboard";
import TransferForm from "@/components/transfer-form/TransferForm";
import type { Payment } from "@/lib/ApiSchemas";

const statusColor: Record<Payment["status"], "success" | "warning" | "error"> = { SUCCESS: "success", PENDING: "warning", FAILED: "error" };
const panel = { p: { xs: 2, md: 3 }, border: 1, borderColor: "divider", height: "100%" };

export default function Dashboard() {
  const intl = useIntl();
  const { state, month, refresh, submit, summarize, signOut, changePage } = useDashboard();
  const label = (id: string) => intl.formatMessage({ id });
  const money = (amount: number) => intl.formatNumber(amount / 100, { style: "currency", currency: "INR" });
  const retry = useCallback(() => void submit(), [submit]);
  const previous = useCallback(() => void changePage(Math.max(0, state.offset - 50)), [changePage, state.offset]);
  const next = useCallback(() => void changePage(state.offset + 50), [changePage, state.offset]);

  if (state.phase === "loading") return <Stack spacing={3} sx={{ minHeight: "100vh", alignItems: "center", justifyContent: "center" }}>
    <CircularProgress /><Typography role="status">{label("loading")}</Typography>
  </Stack>;

  if (state.phase === "guest") return <Container maxWidth="md" component="main" sx={{ py: { xs: 6, md: 14 } }}>
    <Stack spacing={4} sx={{ alignItems: "flex-start" }}>
      <Typography variant="h5" sx={{ fontWeight: 800 }} color="primary">{label("brand")}</Typography>
      <Chip label={label("sandbox")} color="secondary" variant="outlined" />
      <Typography variant="h2" component="h1" sx={{ fontWeight: 800 }}>{label("welcome")}</Typography>
      <Typography variant="h6" color="text.secondary">{label("introduction")}</Typography>
      {state.error && <Alert severity="error">{label(state.error)}</Alert>}
      <Button component="a" href="/auth/login" variant="contained" size="large">{label("login")}</Button>
      <Typography color="text.secondary">{label("privacy")}</Typography>
      <Alert severity="info">{label("sandboxWarning")}</Alert>
    </Stack>
  </Container>;

  return <>
    <Box component="header" sx={{ bgcolor: "background.paper", borderBottom: 1, borderColor: "divider", py: 2 }}>
      <Container maxWidth="xl"><Stack direction="row" sx={{ alignItems: "center", justifyContent: "space-between", gap: 2, flexWrap: "wrap" }}>
        <Stack direction="row" spacing={2} sx={{ alignItems: "center" }}><Typography variant="h5" sx={{ fontWeight: 800 }} color="primary">{label("brand")}</Typography>
          <Chip size="small" label={label("sandbox")} variant="outlined" /></Stack>
        <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}><Typography variant="body2">{state.session?.displayName}</Typography>
          <Button onClick={refresh} disabled={state.busy}>{label("refresh")}</Button><Button onClick={signOut}>{label("logout")}</Button></Stack>
      </Stack></Container>
    </Box>
    <Container component="main" maxWidth="xl" sx={{ py: 4 }}>
      <Stack spacing={3}>
        <Box><Typography variant="h4" component="h1" sx={{ fontWeight: 800 }}>{label("overview")}</Typography>
          <Typography color="text.secondary">{label("tagline")}</Typography></Box>
        {state.error && <Alert severity="error" role="alert">{label(state.error)}</Alert>}
        <Box sx={{ display: "grid", gridTemplateColumns: { xs: "1fr", md: "minmax(0, 1.6fr) minmax(0, 1fr)" }, gap: 3 }}>
          <Stack spacing={3}>
            <Card sx={{ ...panel, bgcolor: "primary.main", color: "primary.contrastText" }}>
              <Stack spacing={2}><Typography>{label("available")}</Typography>
                <Typography variant="h3" component="p" sx={{ fontWeight: 800 }}>{state.wallet ? money(state.wallet.balanceMinor) : "—"}</Typography>
                <Divider sx={{ opacity: 0.3 }} /><Typography variant="caption">{label("walletId")}</Typography>
                <Typography variant="body2" sx={{ overflowWrap: "anywhere", fontFamily: "monospace" }}>{state.wallet?.id ?? "—"}</Typography>
              </Stack>
            </Card>
            {state.pending && <Alert severity={state.activePayment?.status === "PENDING" ? "info" : "warning"}>
              <Stack spacing={1}><Typography>{label(state.activePayment?.status === "PENDING" ? "pendingNotice" : "uncertain")}</Typography>
                <Typography variant="caption" sx={{ overflowWrap: "anywhere" }}>{label("attemptKey")}: {state.pending.key}</Typography>
                {state.activePayment?.status === "PENDING" ? <LinearProgress /> : <Button onClick={retry} disabled={state.busy}>{label("retry")}</Button>}
              </Stack>
            </Alert>}
            {state.activePayment && state.activePayment.status !== "PENDING" && <Alert severity={state.activePayment.status === "SUCCESS" ? "success" : "error"}>
              {label(`status.${state.activePayment.status}`)}{state.activePayment.reason ? ` · ${label(state.activePayment.reason)}` : ""}
            </Alert>}
            <Card sx={panel}><Stack spacing={2}>
              <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>{label("insights")}</Typography>
              <Typography variant="body2" color="text.secondary">{month} · {label("insightsHint")}</Typography>
              {state.report?.categories.length ? state.report.categories.map((category) => <Stack key={category.category} direction="row" sx={{ justifyContent: "space-between" }}>
                <Typography>{label(category.category)}</Typography><Typography sx={{ fontWeight: 700 }}>{money(category.amountMinor)}</Typography>
              </Stack>) : <Typography color="text.secondary">{label("noSpending")}</Typography>}
              <Button variant="outlined" onClick={summarize} disabled={state.busy || !state.report}>{label("summary")}</Button>
              {state.summary && <Alert severity="info"><Stack spacing={1}>
                <Typography sx={{ fontWeight: 700 }}>{label(state.summary.mode === "LLM" ? "localAi" : "ruleSummary")}</Typography>
                <Typography>{state.summary.narrative}</Typography><Typography variant="caption">{label("aiDisclaimer")}</Typography>
              </Stack></Alert>}
            </Stack></Card>
          </Stack>
          <Card sx={panel}><Stack spacing={3}>
            <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>{label("transferTitle")}</Typography>
            <TransferForm walletId={state.wallet?.id ?? ""} balanceMinor={state.wallet?.balanceMinor ?? 0} disabled={state.busy || !state.wallet || Boolean(state.pending)} onSubmit={submit} />
            <Typography variant="caption" color="text.secondary">{label("security")}</Typography>
          </Stack></Card>
        </Box>
        <Card sx={panel}><Stack spacing={2}>
          <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>{label("history")}</Typography>
          <TableContainer><Table aria-label={label("history")}><TableHead><TableRow>
            <TableCell>{label("description")}</TableCell><TableCell>{label("date")}</TableCell><TableCell>{label("recipientColumn")}</TableCell>
            <TableCell align="right">{label("amount")}</TableCell><TableCell>{label("status")}</TableCell>
          </TableRow></TableHead><TableBody>
            {state.payments.map((payment) => <TableRow key={payment.id}>
              <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{payment.description}</Typography><Typography variant="caption" color="text.secondary">{payment.id}</Typography></TableCell>
              <TableCell>{intl.formatDate(payment.createdAt, { year: "numeric", month: "short", day: "numeric" })}</TableCell>
              <TableCell sx={{ maxWidth: 180, overflowWrap: "anywhere" }}>{payment.targetWalletId}</TableCell>
              <TableCell align="right">{money(payment.amountMinor)}</TableCell><TableCell><Chip size="small" variant="outlined" color={statusColor[payment.status]} label={label(`status.${payment.status}`)} /></TableCell>
            </TableRow>)}
            {!state.payments.length && <TableRow><TableCell colSpan={5}>{label("noPayments")}</TableCell></TableRow>}
          </TableBody></Table></TableContainer>
          <Stack direction="row" spacing={1} sx={{ justifyContent: "flex-end" }}><Button onClick={previous} disabled={!state.offset}>{label("previous")}</Button>
            <Button onClick={next} disabled={state.payments.length < 50 || state.offset >= 100000}>{label("next")}</Button></Stack>
        </Stack></Card>
        <Card sx={panel}><Stack spacing={2}>
          <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>{label("notifications")}</Typography>
          {state.notifications.slice(0, 5).map((notification) => <Stack key={notification.payment_id} direction="row" sx={{ justifyContent: "space-between", gap: 2 }}>
            <Typography>{label(notification.status)}</Typography><Typography variant="body2" color="text.secondary" sx={{ overflowWrap: "anywhere" }}>{notification.payment_id}</Typography>
          </Stack>)}
          {!state.notifications.length && <Typography color="text.secondary">{label("noNotifications")}</Typography>}
        </Stack></Card>
        <Typography component="footer" variant="caption" color="text.secondary">{label("sandboxWarning")}</Typography>
      </Stack>
    </Container>
  </>;
}

