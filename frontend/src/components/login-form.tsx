"use client";

import Link from "next/link";
import { useActionState, useState, type FormEvent } from "react";

import { FormField } from "@/components/form-field";
import { SubmitButton } from "@/components/submit-button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { login, type FormState } from "@/lib/auth-actions";
import { validateLogin } from "@/lib/validation";

export function LoginForm({ next }: { next?: string }) {
  const [state, formAction] = useActionState<FormState, FormData>(login, {});
  const [clientErrors, setClientErrors] = useState<Record<string, string>>();
  const errors = clientErrors ?? state.fieldErrors ?? {};

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    const data = new FormData(event.currentTarget);
    const found = validateLogin({
      email: String(data.get("email") ?? ""),
      password: String(data.get("password") ?? ""),
    });
    const invalid = Object.keys(found).length > 0;
    setClientErrors(invalid ? found : undefined);
    if (invalid) event.preventDefault();
  }

  return (
    <Card className="w-full max-w-sm">
      <CardHeader>
        <CardTitle>Entrar</CardTitle>
        <CardDescription>Acesse sua conta no Atlas Inventory.</CardDescription>
      </CardHeader>
      <CardContent>
        <form action={formAction} onSubmit={onSubmit} noValidate className="grid gap-4">
          {next && <input type="hidden" name="next" value={next} />}
          {state.formError && (
            <p role="alert" className="text-sm text-destructive">
              {state.formError}
            </p>
          )}
          <FormField
            id="email"
            label="E-mail"
            type="email"
            autoComplete="email"
            defaultValue={state.values?.email}
            error={errors.email}
          />
          <FormField
            id="password"
            label="Senha"
            type="password"
            autoComplete="current-password"
            error={errors.password}
          />
          <SubmitButton label="Entrar" pendingLabel="Entrando…" />
          <p className="text-center text-sm text-muted-foreground">
            Não tem conta?{" "}
            <Link href="/cadastro" className="underline underline-offset-4">
              Criar conta
            </Link>
          </p>
        </form>
      </CardContent>
    </Card>
  );
}
