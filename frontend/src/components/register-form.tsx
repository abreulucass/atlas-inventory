"use client";

import Link from "next/link";
import { useActionState, useState, type FormEvent } from "react";

import { FormField } from "@/components/form-field";
import { SubmitButton } from "@/components/submit-button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { register, type FormState } from "@/lib/auth-actions";
import { slugify, validateRegister } from "@/lib/validation";

export function RegisterForm() {
  const [state, formAction] = useActionState<FormState, FormData>(register, {});
  const [clientErrors, setClientErrors] = useState<Record<string, string>>();
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [organizationName, setOrganizationName] = useState("");
  const [slug, setSlug] = useState("");
  const [slugTouched, setSlugTouched] = useState(false);
  const errors = clientErrors ?? state.fieldErrors ?? {};

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    const password = String(new FormData(event.currentTarget).get("password") ?? "");
    const found = validateRegister({
      name,
      email,
      password,
      organizationName,
      organizationSlug: slug,
    });
    const invalid = Object.keys(found).length > 0;
    setClientErrors(invalid ? found : undefined);
    if (invalid) event.preventDefault();
  }

  return (
    <Card className="w-full max-w-sm">
      <CardHeader>
        <CardTitle>Criar conta</CardTitle>
        <CardDescription>Cadastre-se e crie a sua organização.</CardDescription>
      </CardHeader>
      <CardContent>
        <form action={formAction} onSubmit={onSubmit} noValidate className="grid gap-4">
          {state.formError && (
            <p role="alert" className="text-sm text-destructive">
              {state.formError}
            </p>
          )}
          <FormField
            id="name"
            label="Nome"
            autoComplete="name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            error={errors.name}
          />
          <FormField
            id="email"
            label="E-mail"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            error={errors.email}
          />
          <FormField
            id="password"
            label="Senha"
            type="password"
            autoComplete="new-password"
            error={errors.password}
          />
          <FormField
            id="organizationName"
            label="Nome da organização"
            autoComplete="organization"
            value={organizationName}
            onChange={(e) => {
              setOrganizationName(e.target.value);
              if (!slugTouched) setSlug(slugify(e.target.value));
            }}
            error={errors.organizationName}
          />
          <FormField
            id="organizationSlug"
            label="Endereço da organização"
            autoComplete="off"
            value={slug}
            onChange={(e) => {
              setSlugTouched(true);
              setSlug(e.target.value);
            }}
            error={errors.organizationSlug}
          />
          <SubmitButton label="Criar conta" pendingLabel="Criando conta…" />
          <p className="text-center text-sm text-muted-foreground">
            Já tem conta?{" "}
            <Link href="/entrar" className="underline underline-offset-4">
              Entrar
            </Link>
          </p>
        </form>
      </CardContent>
    </Card>
  );
}
