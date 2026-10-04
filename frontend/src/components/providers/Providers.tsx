"use client";

import { useState } from "react";
import { Provider } from "react-redux";
import { IntlProvider } from "react-intl";
import { AppRouterCacheProvider } from "@mui/material-nextjs/v16-appRouter";
import { createTheme, CssBaseline, ThemeProvider } from "@mui/material";
import { EN_MESSAGES } from "@/i18n/MessageConstants";
import { makeStore } from "@/store/FinpaySlice";

const theme = createTheme({
  palette: { primary: { main: "#4f46e5" }, secondary: { main: "#0f766e" },
    background: { default: "#f5f7fb", paper: "#ffffff" }, text: { primary: "#0f172a", secondary: "#64748b" } },
  shape: { borderRadius: 16 },
  typography: { fontFamily: "Arial, Helvetica, sans-serif", button: { textTransform: "none", fontWeight: 700 } },
  components: { MuiButton: { defaultProps: { disableElevation: true } }, MuiCard: { defaultProps: { elevation: 0 } } },
});

export default function Providers({ children, nonce }: { children: React.ReactNode; nonce?: string }) {
  const [store] = useState(makeStore);
  return (
    <AppRouterCacheProvider options={{ nonce }}>
      <Provider store={store}>
        <IntlProvider locale="en-IN" messages={EN_MESSAGES}>
          <ThemeProvider theme={theme}><CssBaseline />{children}</ThemeProvider>
        </IntlProvider>
      </Provider>
    </AppRouterCacheProvider>
  );
}


