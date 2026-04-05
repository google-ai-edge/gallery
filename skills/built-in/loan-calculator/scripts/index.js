/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

function fmt(n, currency) {
  const formatted = new Intl.NumberFormat('en-US', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 0,
  }).format(Math.round(n));
  return currency ? `${formatted} ${currency}` : formatted;
}

/**
 * Standard amortizing loan: monthly payment given principal, rate, term.
 * Formula: M = P * [r(1+r)^n] / [(1+r)^n - 1]
 */
function calcMonthlyPayment(principal, annualRate, years) {
  const n = years * 12;
  if (annualRate === 0) return principal / n;
  const r = annualRate / 100 / 12;
  return principal * (r * Math.pow(1 + r, n)) / (Math.pow(1 + r, n) - 1);
}

/**
 * Reverse: max principal given monthly payment, rate, term.
 * P = M * [(1+r)^n - 1] / [r(1+r)^n]
 */
function calcMaxPrincipal(monthlyPayment, annualRate, years) {
  const n = years * 12;
  if (annualRate === 0) return monthlyPayment * n;
  const r = annualRate / 100 / 12;
  return monthlyPayment * (Math.pow(1 + r, n) - 1) / (r * Math.pow(1 + r, n));
}

window['ai_edge_gallery_get_result'] = async (dataStr) => {
  try {
    const input = JSON.parse(dataStr);
    const currency = (input.currency || '').trim();
    const annualRate = parseFloat(input.annual_rate);
    const years = parseFloat(input.years);

    if (isNaN(annualRate) || annualRate < 0) {
      return JSON.stringify({ error: 'annual_rate must be a non-negative number (e.g. 4.5 for 4.5%).' });
    }
    if (isNaN(years) || years <= 0) {
      return JSON.stringify({ error: 'years must be a positive number.' });
    }

    const n = Math.round(years * 12);

    // Reverse calculation: how much can I borrow?
    if (input.monthly_payment !== undefined) {
      const monthlyPayment = parseFloat(input.monthly_payment);
      if (isNaN(monthlyPayment) || monthlyPayment <= 0) {
        return JSON.stringify({ error: 'monthly_payment must be a positive number.' });
      }
      const maxPrincipal = calcMaxPrincipal(monthlyPayment, annualRate, years);
      const totalPaid = monthlyPayment * n;
      const totalInterest = totalPaid - maxPrincipal;

      return JSON.stringify({
        result: `With ${fmt(monthlyPayment, currency)}/month at ${annualRate}% over ${years} years, you can borrow up to ${fmt(maxPrincipal, currency)}.`,
        max_loan_amount: Math.round(maxPrincipal),
        monthly_payment: Math.round(monthlyPayment),
        total_paid: Math.round(totalPaid),
        total_interest: Math.round(totalInterest),
        annual_rate_pct: annualRate,
        term_years: years,
        term_months: n,
        currency: currency || null,
      });
    }

    // Standard calculation: what is my monthly payment?
    const principal = parseFloat(input.principal);
    if (isNaN(principal) || principal <= 0) {
      return JSON.stringify({ error: 'principal must be a positive number, or provide monthly_payment for reverse calculation.' });
    }

    const monthly = calcMonthlyPayment(principal, annualRate, years);
    const totalPaid = monthly * n;
    const totalInterest = totalPaid - principal;
    const interestPct = (totalInterest / principal) * 100;

    // Build an amortization summary (first year, midpoint, last year)
    let schedule = [];
    let balance = principal;
    const r = annualRate / 100 / 12;
    for (let i = 1; i <= n; i++) {
      const interestPayment = balance * r;
      const principalPayment = monthly - interestPayment;
      balance = Math.max(0, balance - principalPayment);
      if (i === 12 || i === Math.round(n / 2) || i === n) {
        schedule.push({ month: i, remaining_balance: Math.round(balance) });
      }
    }

    return JSON.stringify({
      result: `Loan of ${fmt(principal, currency)} at ${annualRate}% over ${years} years → ${fmt(monthly, currency)}/month. Total paid: ${fmt(totalPaid, currency)} (${fmt(totalInterest, currency)} in interest, ${interestPct.toFixed(1)}% of principal).`,
      monthly_payment: Math.round(monthly),
      principal: Math.round(principal),
      total_paid: Math.round(totalPaid),
      total_interest: Math.round(totalInterest),
      interest_as_pct_of_principal: parseFloat(interestPct.toFixed(1)),
      annual_rate_pct: annualRate,
      term_years: years,
      term_months: n,
      currency: currency || null,
      balance_milestones: schedule,
    });
  } catch (e) {
    return JSON.stringify({ error: `Loan calculation failed: ${e.message}` });
  }
};
