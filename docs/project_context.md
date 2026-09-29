# Project Context

## 1. Original problem

Small and medium retailers increasingly operate through multiple channels, including:

- physical stores;
- e-commerce;
- marketplaces;
- other digital sales channels.

This creates operational fragmentation across product information, stock, orders, fiscal processes, and physical operations.

The original hackathon problem highlights high manual rework in:

- product-registration unification;
- inventory synchronization;
- fiscal processing;
- multichannel operations.

The expected direction is a simple, accessible, centralized platform supported by modern APIs, cloud architecture, process automation, and artificial intelligence.

---

## 2. Problem refinement

The project does **not** assume that basic omnichannel integration is an unsolved market problem.

Existing ERP and marketplace platforms already cover relevant portions of:

- multichannel integration;
- catalog;
- stock;
- orders;
- shipping;
- production and inventory workflows.

Therefore, the project narrows the problem.

The opportunity investigated here is the failure that remains when:

- two systems disagree;
- a system is internally consistent but differs from physical reality;
- an expected downstream event never occurs;
- a recurring operational problem remains manual because an ERP customization is too expensive or too specific.

---

## 3. Central product thesis

> A transactional system may appear correct while the real operational state is inconsistent.

Examples:

- a sale was recorded but inventory was not updated;
- a product physically left the company without a correlated fiscal document;
- a physical receipt differs from the quantity registered digitally;
- a physical count differs from expected inventory;
- a product was lost, broken, removed, or moved without the appropriate digital record;
- a recurrent process is handled manually because implementing it inside the ERP is economically unattractive.

---

## 4. Product definition

The product is:

> A complementary operational reconciliation layer that monitors events from sales, inventory, fiscal, marketplace, and physical-operation systems, correlates them, detects inconsistencies, and transforms divergences into explainable and actionable exceptions.

Existing systems remain responsible for transactional processing.

For the product-registration-unification part of Problemática 1, PEEKio also
keeps a minimal canonical operational product record and initiates its
propagation to user-selected systems through adapters. Those systems remain
responsible for their own resulting records. This bounded registration flow
does not make PEEKio a complete catalog or marketplace hub.

This product is responsible for:

- observation;
- normalization;
- correlation;
- expected-state reconstruction;
- exception detection;
- evidence collection;
- recommendation.
- minimal canonical product registration, cross-system mapping, and traceable
  propagation attempts.

---

## 5. Operational Truth

The product works around a central concept: **Operational Truth**.

It compares three perspectives.

### 5.1 What should have happened

The process or operational relationship expected by the business.

Example:

```text
SALE_CONFIRMED
    ↓
STOCK_UPDATED
```

### 5.2 What systems recorded

What ERP, inventory, fiscal, sales, marketplace, or other systems report.

### 5.3 What physically happened

When physical evidence exists:

- physical count;
- receipt;
- exit;
- manual adjustment;
- barcode/QR capture;
- other operational event.

Conceptually:

```text
What should have happened
            ×
What systems recorded
            ×
What physically happened
            ↓
     OPERATIONAL TRUTH
```

The MVP does not claim to reconstruct perfect real-world truth. It correlates available evidence to detect inconsistencies earlier.

---

## 6. Product principle: Exception First

The manager should not need to continuously inspect several systems to discover whether something is wrong.

Normal operation may remain silent.

The system should surface only meaningful deviations.

```text
EVENTS
   ↓
NORMALIZATION
   ↓
OPERATIONAL STATE
   ↓
RULES + CORRELATION
   ↓
Problem?
 ├── no → no intervention
 └── yes
       ↓
   EXCEPTION
       ↓
   EVIDENCE
       ↓
    IMPACT
       ↓
RECOMMENDED ACTION
```

---

## 7. Value proposition

### Primary message

> Detect automatically when real operation stops matching what existing systems believe happened.

### Complementary message

> Automate operational problems that are too small to justify an expensive ERP customization, but too recurrent to continue being handled manually.

---

## 8. Target organizations

The primary context is small and medium businesses that:

- operate in more than one sales channel;
- use multiple operational systems;
- have an ERP or equivalent transaction system;
- still depend on manual conference, spreadsheets, WhatsApp, or human reconciliation for part of the operation;
- cannot or do not want to replace the ERP.

The project is especially relevant when operational information is split across:

- sales;
- inventory;
- fiscal;
- marketplaces;
- physical processes;
- internal systems.

---

## 9. Main persona

For the MVP, the visible user can be represented as an **operations manager**.

This person needs to answer questions such as:

- What is wrong right now?
- Which system or event caused the alert?
- What was expected?
- What actually happened?
- What is the likely impact?
- What should I investigate or do next?

Other stakeholders may include:

- stock/inventory staff;
- back office;
- logistics;
- fiscal/administrative teams;
- business owner.

---

## 10. Strategic boundary

The product should not be positioned primarily as:

- another ERP;
- another marketplace hub;
- another WMS;
- another inventory system;
- another dashboard;
- generic "AI for retail";
- generic automation software.

The differentiating thesis is:

> Expected operational relationships are monitored continuously, and deviations become traceable, explainable, actionable exceptions.

---

## 11. Why AI is secondary to instrumentation

AI is not the product by itself.

The primary asset is the structured operational event stream and the relationships between events.

AI/JEV should enter after:

- events are captured;
- data is normalized;
- objective rules have been applied;
- evidence is available.

Its role is mainly to:

- explain;
- contextualize;
- suggest probable causes;
- prioritize;
- recommend investigation or action.

---

## 12. Product success for the hackathon

The MVP succeeds if the demonstration proves that:

1. different source systems can emit independent events;
2. those events can be normalized;
3. the platform can infer what should have happened;
4. a deliberate operational violation can be detected;
5. the alert contains traceable evidence;
6. the user understands the difference between expected and observed state;
7. the platform provides a useful recommended next action.
