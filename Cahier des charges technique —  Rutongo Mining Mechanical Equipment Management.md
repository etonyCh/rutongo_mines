# Cahier des charges technique — Rutongo Mines Mechanical Equipment Management System (Kotlin)

Version 1.0 — 5 novembre 2026 — Document de référence pour la réalisation, sprint par sprint, du système décrit dans le « Master Build Prompt ».

## 0. Analyse du document source

### 0.1 Nature du besoin

Le Master Build Prompt décrit une **GMAO (CMMS) minière** complète : inspection quotidienne obligatoire, suivi d'état du parc, pannes et réparations, maintenance préventive par heures moteur, pièces détachées, personnel, rapports PDF quotidiens avec workflow d'approbation, tableaux de bord par rôle, audit et import Excel. Ce n'est pas un site vitrine : le document impose base persistante, sécurité côté serveur, historique immuable et aucune donnée inventée.

| Élément | Valeur imposée |
| --- | --- |
| Organisation | Rutongo mines, Mechanical Department |
| Sites initiaux | Nyamyumba, Sanzari (orthographe officielle), Masoro, Karambo, Gasambya, Workshop |
| Rôles | Mechanic, Supervisor, Manager, General Manager, Administrator (exactement 5) |
| Statuts opérationnels | GREEN Operational, ORANGE Under Repair, RED Down (exactement 3) |
| Équipes (shifts) | Day, Red, Night (avec passage de minuit) |
| Types d'équipement initiaux | 10, extensibles |
| États d'inspection | Not Started, In Progress, Completed, Completed With Defects, Critical Failure, Missing |
| Sources d'import | All Assets.xlsx, Engineering Maintenance Team.xlsx, Operators by Site.xlsx |
| Tests d'acceptation | 35 |

### 0.2 Invariants non négociables (extraits du document)

1. Exactement trois statuts opérationnels. « Operational With Defects » est une condition dérivée, jamais un quatrième statut.
2. Historique immuable : événements de statut, inspections soumises, journal d'audit, rapports approuvés. Corrections par nouvel enregistrement lié à l'original.
3. Aucune suppression physique des données historiques (sites, tunnels, équipements, maintenance, rapports).
4. Aucune donnée inventée : valeur absente affichée « Not Recorded » ; inspection manquante ≠ équipement opérationnel ; 0 h enregistrée ≠ équipement à l'arrêt.
5. Contrôle d'accès côté serveur, avec portée par site.
6. Horodatages stockés en UTC, affichés dans le fuseau configuré ; affectation aux shifts par bornes configurées.
7. La génération de rapport ne modifie jamais les données opérationnelles ; un rapport approuvé ne change plus (snapshot + empreinte du PDF).
8. Import Excel idempotent, avec prévisualisation, rejets motivés, sans suppression silencieuse.
9. Durées calculées à partir de relevés valides, sans double comptage (union d'intervalles).
10. Une fonctionnalité n'est « terminée » qu'après implémentation et tests.

### 0.3 Ambiguïtés et incohérences détectées, avec décision proposée

| # | Constat dans le document | Décision proposée (à valider) |
| --- | --- | --- |
| A1 | L'en-tête PDF contient une ligne fixe « WORKSHOP • DAILY MAINTENANCE & ASSET STATUS » alors que le rapport est produit par site. | Ligne dynamique : `{SITE EN MAJUSCULES} • DAILY MAINTENANCE & ASSET STATUS`. « WORKSHOP » n'apparaît que pour le site Workshop. |
| A2 | Le motif du nom de fichier `[SITE][YYYY-MM-DD][SHIFT]` a perdu ses séparateurs. | `RUTONGO_MINES_DAILY_REPORT_{SITE}_{YYYY-MM-DD}_{SHIFT}.pdf`, avec `FULL_DAY` pour la journée complète. |
| A3 | « Mechanic » (rôle) et « technician » (personnel importé) sont deux notions. | `User(role=MECHANIC)` est lié à 0..1 `Technician`. Les opérateurs restent une entité distincte, sans compte. |
| A4 | « Authorized sites » sans modèle d'autorisation. | Table `user_site_access`. Mechanic et Supervisor : sites explicitement affectés. Manager, General Manager, Administrator : tous les sites par défaut, restreignables. |
| A5 | Cycle de vie Active/Inactive/Retired et statut opérationnel peuvent être confondus. | Deux axes séparés : `lifecycle_state` et `current_status`. |
| A6 | Statut initial des équipements importés non défini, et le prompt interdit un 4e statut. | `current_status` nul tant qu'aucun événement n'existe, affiché « Not Recorded ». Ce n'est pas un statut. |
| A7 | Fuseau horaire non précisé. | Paramètre système, défaut `Africa/Kigali` (UTC+2, déduit des noms de sites), surchargeable par site. À confirmer. |
| A8 | Devise non précisée. | Montants en unités mineures (`bigint`) + code devise configurable. À confirmer (USD ou RWF). |
| A9 | « Digital signature or authenticated attestation ». | Attestation authentifiée : ressaisie du mot de passe ou d'un PIN, stockée avec empreinte, nom, EID et horodatage. Signature dessinée optionnelle. |
| A10 | Travail sous terre « subject to connectivity », mais pas d'exigence hors-ligne explicite. | Application Android **offline-first** pour inspections et mises à jour de statut. Le serveur reste maître (voir règles de synchronisation, § 6.7). |
| A11 | Horodatage de soumission « immuable » vs saisie hors-ligne. | Deux champs : `captured_at` (client, informatif) et `submitted_at` (serveur, immuable, fait foi). |
| A12 | Inspection soumise après fermeture de fenêtre. | L'état « Missing » est conservé dans l'historique de l'attente ; l'inspection tardive est acceptée avec `submitted_late = true`, visible dans les rapports. |
| A13 | Fichiers Excel non fournis, colonnes inconnues. | Import par **profils de mapping** configurables, avec extraction conservant toutes les colonnes dans `extra` (jsonb). Aucun nom de colonne codé en dur avant d'avoir vu les fichiers. |
| A14 | « Relevant maintenance costs, when authorized » sans règle. | Permission `COST_VIEW` : Manager, General Manager, Administrator. Superviseur : configurable. |
| A15 | Unicité des rapports approuvés par site/date/période, mais rapport FULL\_DAY et rapports par shift coexistent. | Unicité calculée sur (site, date, période) où la période est `DAY`, `RED`, `NIGHT` ou `FULL_DAY`. Une seule version « courante approuvée » par triplet. |
| A16 | Lien Paper fourni. | Page non lisible (application JavaScript derrière connexion). Les écrans seront alignés sur vos maquettes dès réception d'exports (PNG/PDF ou tokens de design). |

### 0.4 Périmètre de livraison de ce document

Une base de code complète pour ce périmètre représente environ 40 000 à 60 000 lignes. Elle ne tient pas dans un seul document. Ce cahier des charges fournit donc : les décisions d'architecture, la structure du dépôt, le schéma de données, le **code de référence des composants critiques** (domaine, règles métier, sécurité, import, PDF, tests), le catalogue d'API et le plan de sprints. Chaque sprint peut ensuite être généré module par module, sur ces fondations, avec ses tests.

## 1. Décisions d'architecture

| Domaine | Choix | Justification | Alternative écartée |
| --- | --- | --- | --- |
| Langage | Kotlin 2.x, JDK 21 | Exigence du projet ; code partagé serveur/clients | — |
| Style | **Monolithe modulaire** hexagonal | Une équipe, un déploiement, transactions simples ; frontières imposées par tests d'architecture | Microservices (surcoût sans bénéfice) |
| Backend | Ktor 3 (Netty) | Léger, coroutines natives, tests intégrés (`testApplication`) | Spring Boot (plus lourd) |
| Base de données | PostgreSQL 16 | Transactions, contraintes, `timestamptz`, `jsonb`, index partiels, triggers d'immutabilité | MySQL |
| Accès données | Exposed (DSL) + HikariCP + Flyway | Idiomatique Kotlin, migrations versionnées | jOOQ (licence/génération) |
| Injection | Koin | Simple, adapté à Ktor | Dagger/Hilt côté serveur |
| Sérialisation | kotlinx.serialization | Multiplateforme, DTO partagés | Jackson |
| Dates | `kotlinx-datetime` (API), `java.time` (infra) | UTC partout, conversion en bordure | — |
| Authentification | JWT d'accès court (15 min) + refresh token rotatif haché en base ; mots de passe Argon2id | Standard éprouvé, révocation possible | Sessions cookies (moins adapté aux apps natives) |
| PDF | OpenPDF (polices TTF embarquées) | PDF réel côté serveur, pagination maîtrisée, licence permissive | HTML→PDF (rendu moins prévisible) |
| Excel | Apache POI (lecture streaming pour gros fichiers) | Référence JVM | — |
| Fichiers | Interface `BlobStore` : S3 compatible (MinIO) en production, disque en dev | Pas d'URL publique ; accès par endpoint autorisé | URLs S3 publiques (interdit) |
| Tâches planifiées | db-scheduler (persistant, verrouillage en base) | Un seul exécutant même avec plusieurs instances | Timers en mémoire |
| Temps réel | SSE (Ktor) + push FCM sur Android | Quasi temps réel, simple, traverse les proxys | WebSocket (complexité inutile en lecture seule) |
| Clients | **Kotlin Multiplatform** : `shared` + Android (Compose, SQLDelight, WorkManager) + Web (Compose Multiplatform Wasm) | Un seul langage ; hors-ligne natif pour mécaniciens ; web pour direction | Web seul (inadapté au sous-terrain) |
| Observabilité | Logback JSON, Micrometer/Prometheus, corrélation `X-Request-Id` | Exploitable en production | — |
| Déploiement | Docker Compose (app, Postgres, MinIO, Caddy TLS), CI GitHub Actions | Simple à opérer, documenté | Kubernetes (prématuré) |

**Plan B client web** : si Compose Wasm s'avère insuffisant (poids, accessibilité), remplacer `webApp` par Kotlin/JS avec kotlin-wrappers React. Les DTO, validations et clients API restent dans `shared`, donc le coût de bascule est limité.

### 1.1 Règles de dépendance entre couches

```
api (routes, DTO HTTP)  ->  application (use cases)  ->  domain (entités, règles pures)
infrastructure (Exposed, S3, PDF, POI)  ->  implémente les ports définis dans domain/application
```

- `domain` ne dépend d'aucune bibliothèque technique (ni Ktor, ni Exposed).
- Un module ne lit pas les tables d'un autre module : il passe par son port public.
- Lectures lourdes (tableaux de bord, rapports) : services de requête dédiés (CQRS léger), jamais d'écriture.
- Ces règles sont vérifiées automatiquement par Konsist dans la CI.

## 2. Structure du dépôt

```
rutongo-mmems/
├─ build.gradle.kts / settings.gradle.kts / gradle/libs.versions.toml
├─ build-logic/                      # conventions Gradle (kotlin-jvm, kmp, detekt, kover)
├─ shared/                           # Kotlin Multiplatform
│  └─ src/commonMain/kotlin/rw/rutongo/mmems/shared/
│     ├─ dto/                        # DTO API (@Serializable)
│     ├─ enums/                      # Role, OperationalStatus, InspectionState, ...
│     ├─ validation/                 # règles partagées client/serveur
│     └─ time/                       # ShiftResolver pur (testé en commun)
├─ server/
│  ├─ src/main/kotlin/rw/rutongo/mmems/
│  │  ├─ Application.kt              # module Ktor, plugins
│  │  ├─ config/                     # AppConfig (env), Koin modules
│  │  ├─ platform/                   # transverses : auth, rbac, audit, errors, idempotency,
│  │  │                              #   clock, blobstore, scheduler, sse, metrics
│  │  └─ modules/
│  │     ├─ identity/                # users, sessions, user_site_access
│  │     ├─ organization/            # sites, tunnels, shifts, schedules
│  │     ├─ equipment/               # master data, types, movements
│  │     ├─ status/                  # événements de statut, dérivation
│  │     ├─ inspection/              # checklists, brouillons, soumission, contrôle
│  │     ├─ defect/
│  │     ├─ breakdown/
│  │     ├─ maintenance/             # services, planification, vérification
│  │     ├─ workinghours/
│  │     ├─ spareparts/
│  │     ├─ personnel/               # technicians, operators
│  │     ├─ notification/
│  │     ├─ dashboard/               # services de lecture
│  │     ├─ report/                  # snapshot, PDF, workflow, historique
│  │     ├─ importer/                # profils de mapping, preview, commit
│  │     └─ exporter/
│  │        # chaque module : api/ application/ domain/ infrastructure/
│  ├─ src/main/resources/db/migration/   # V001__..., V002__...
│  ├─ src/main/resources/fonts/          # TTF embarquées pour le PDF
│  ├─ src/test/                          # unit
│  └─ src/integrationTest/               # Testcontainers
├─ androidApp/                       # Compose, offline-first (SQLDelight, WorkManager, CameraX)
├─ webApp/                           # Compose Multiplatform Wasm
├─ ops/                              # docker-compose.yml, Caddyfile, backup scripts, runbooks
├─ docs/                             # ADR, OpenAPI, guide d'import, guide d'exploitation
└─ .github/workflows/                # ci.yml, release.yml, security.yml
```

## 3. Configuration du build (extrait `libs.versions.toml`)

Figer la dernière version stable de chaque dépendance au Sprint 0, puis laisser Renovate proposer les mises à jour.

```toml
[versions]
kotlin = "2.x"; ktor = "3.x"; exposed = "0.5x"; koin = "4.x"; flyway = "10.x"
kotest = "5.x"; mockk = "1.13.x"; testcontainers = "1.20.x"; sqldelight = "2.x"
openpdf = "2.x"; poi = "5.x"; argon2 = "2.x"; dbScheduler = "15.x"; kover = "0.9.x"

[libraries]
ktor-server-core = { module = "io.ktor:ktor-server-core-jvm", version.ref = "ktor" }
ktor-server-netty = { module = "io.ktor:ktor-server-netty-jvm", version.ref = "ktor" }
ktor-server-auth-jwt = { module = "io.ktor:ktor-server-auth-jwt-jvm", version.ref = "ktor" }
ktor-server-content-negotiation = { module = "io.ktor:ktor-server-content-negotiation-jvm", version.ref = "ktor" }
ktor-server-status-pages = { module = "io.ktor:ktor-server-status-pages-jvm", version.ref = "ktor" }
ktor-server-rate-limit = { module = "io.ktor:ktor-server-rate-limit-jvm", version.ref = "ktor" }
ktor-server-cors = { module = "io.ktor:ktor-server-cors-jvm", version.ref = "ktor" }
ktor-server-call-id = { module = "io.ktor:ktor-server-call-id-jvm", version.ref = "ktor" }
ktor-server-sse = { module = "io.ktor:ktor-server-sse-jvm", version.ref = "ktor" }
ktor-server-test-host = { module = "io.ktor:ktor-server-test-host-jvm", version.ref = "ktor" }
exposed-core = { module = "org.jetbrains.exposed:exposed-core", version.ref = "exposed" }
exposed-jdbc = { module = "org.jetbrains.exposed:exposed-jdbc", version.ref = "exposed" }
exposed-kotlin-datetime = { module = "org.jetbrains.exposed:exposed-kotlin-datetime", version.ref = "exposed" }
flyway-core = { module = "org.flywaydb:flyway-core", version.ref = "flyway" }
argon2-jvm = { module = "de.mkammerer:argon2-jvm", version.ref = "argon2" }
openpdf = { module = "com.github.librepdf:openpdf", version.ref = "openpdf" }
poi-ooxml = { module = "org.apache.poi:poi-ooxml", version.ref = "poi" }
testcontainers-postgres = { module = "org.testcontainers:postgresql", version.ref = "testcontainers" }
kotest-runner = { module = "io.kotest:kotest-runner-junit5", version.ref = "kotest" }
```

Qualité imposée par la CI : `ktlint`, `detekt` (échec sur avertissement), `kover` (seuils de couverture, § 13), tests d'architecture Konsist, OWASP Dependency-Check.

## 4. Modèle de données PostgreSQL

### 4.1 Conventions

- Clés primaires `uuid` (v7 générées par l'application, ordonnables dans le temps).
- Tous les instants en `timestamptz` (UTC). Les dates de rapport en `date` + fuseau du site.
- Colonnes communes : `created_at`, `updated_at`, `created_by`, `version bigint` (verrouillage optimiste sur les tables modifiables).
- Montants en `bigint` (unités mineures) + `currency char(3)`.
- États par colonnes `text` + `CHECK`, jamais de `DELETE` sur tables historiques.
- **Tables immuables** : un trigger `forbid_update_delete()` rejette `UPDATE` et `DELETE` sur `equipment_status_event`, `inspection`, `inspection_answer`, `audit_event`, `stock_movement`, `report_version`, `equipment_movement`. Seule exception contrôlée : colonnes techniques explicitement listées (aucune pour l'instant).
- Tout ce qui est importé conserve les colonnes d'origine dans `extra jsonb` et la référence `import_row_id`.

### 4.2 Organisation et identité

```sql
create table site (
  id uuid primary key, code text not null unique, name text not null,
  state text not null check (state in ('ACTIVE','INACTIVE','DISABLED','RETIRED')),
  timezone text not null default 'Africa/Kigali',
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  version bigint not null default 0);

create table tunnel (
  id uuid primary key, site_id uuid not null references site(id),
  code text not null, name text not null,
  state text not null check (state in ('ACTIVE','INACTIVE','DISABLED','RETIRED')),
  unique (site_id, code));

create table shift (
  id uuid primary key, code text not null unique check (code in ('DAY','RED','NIGHT')),
  name text not null);

-- bornes configurables, globales (site_id null) ou par site, avec historique de validité
create table shift_schedule (
  id uuid primary key, shift_id uuid not null references shift(id),
  site_id uuid references site(id),
  start_local time not null, end_local time not null,      -- end < start => passe minuit
  inspection_window_minutes int not null,                  -- fenêtre d'inspection après début de shift
  hard_backstop_hours int not null default 24,
  effective_from date not null, effective_to date);

create table app_user (
  id uuid primary key, username citext not null unique, full_name text not null,
  employee_id text not null unique, role text not null
    check (role in ('MECHANIC','SUPERVISOR','MANAGER','GENERAL_MANAGER','ADMINISTRATOR')),
  phone text, home_site_id uuid references site(id), tunnel_id uuid references tunnel(id),
  shift_id uuid references shift(id), technician_id uuid,   -- lien optionnel (A3)
  password_hash text not null, must_change_password boolean not null default true,
  failed_attempts int not null default 0, locked_until timestamptz,
  active boolean not null default true, last_login_at timestamptz,
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  version bigint not null default 0);

create table user_site_access (user_id uuid references app_user(id), site_id uuid references site(id),
  primary key (user_id, site_id));

create table refresh_token (
  id uuid primary key, user_id uuid not null references app_user(id),
  token_hash text not null unique, family_id uuid not null,
  issued_at timestamptz not null, expires_at timestamptz not null,
  used_at timestamptz, revoked_at timestamptz, device_label text);
```

### 4.3 Équipement et statut

```sql
create table equipment_type (id uuid primary key, name text not null unique, source text not null default 'MANUAL', active boolean not null default true);

create table equipment (
  id uuid primary key, asset_id text not null unique, equipment_code text not null,
  name text not null, description text, type_id uuid not null references equipment_type(id),
  model text, serial_number text, manufacture_year int,
  site_id uuid not null references site(id), tunnel_id uuid references tunnel(id),
  lifecycle_state text not null default 'ACTIVE' check (lifecycle_state in ('ACTIVE','INACTIVE','RETIRED')),
  requires_daily_inspection boolean not null default true,
  current_status text check (current_status in ('OPERATIONAL','UNDER_REPAIR','DOWN')),   -- null = Not Recorded (A6)
  current_status_event_id uuid, current_problem text, current_shift_id uuid references shift(id),
  assigned_technician_id uuid, assigned_operator_id uuid,
  engine_hours_current numeric(10,1), last_inspection_id uuid, last_maintenance_id uuid,
  last_status_update_at timestamptz, last_status_reported_by uuid,
  remarks text, extra jsonb not null default '{}', import_row_id uuid,
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  version bigint not null default 0);
create index on equipment (site_id, lifecycle_state);
create index on equipment (current_status) where lifecycle_state = 'ACTIVE';

create table equipment_status_event (      -- IMMUABLE
  id uuid primary key, equipment_id uuid not null references equipment(id),
  seq bigint not null, previous_status text, new_status text not null
    check (new_status in ('OPERATIONAL','UNDER_REPAIR','DOWN')),
  reason text, problem text, action_taken text, parts_required text, remarks text,
  site_id uuid not null, tunnel_id uuid, shift_id uuid,
  technician_ids uuid[] not null default '{}', user_id uuid not null, user_eid text not null,
  occurred_at timestamptz not null, recorded_at timestamptz not null default now(),
  source text not null check (source in ('MANUAL','INSPECTION','BREAKDOWN','SYSTEM_RULE','CORRECTION','IMPORT')),
  breakdown_id uuid, corrects_event_id uuid references equipment_status_event(id),
  override_reason text, idempotency_key text unique,
  unique (equipment_id, seq));
create index on equipment_status_event (equipment_id, seq desc);
create index on equipment_status_event (site_id, occurred_at desc);

create table equipment_movement (          -- IMMUABLE
  id uuid primary key, equipment_id uuid not null references equipment(id),
  from_site_id uuid, from_tunnel_id uuid, to_site_id uuid not null, to_tunnel_id uuid,
  moved_at timestamptz not null, user_id uuid not null, reason text);
```

La suite du modèle (inspection, défauts, pannes, maintenance, pièces, rapports, import, audit, notifications) et le code de référence figurent dans la suite du document.

### 4.4 Inspection, défauts, photos

```sql
create table checklist_item (               -- administrable sans code
  id uuid primary key, category text not null check (category in ('SAFETY','MECHANICAL')),
  code text not null unique, label text not null, sort_order int not null,
  mandatory boolean not null default true, safety_critical boolean not null default false,
  photo_required_on_fail boolean not null default false, na_reason_required boolean not null default true,
  applies_to_type_ids uuid[], active boolean not null default true);
-- Seed SAFETY : Seat Belt, Horn, Lights, Reverse Alarm, Braking System, Fire Extinguisher, General Safety Devices
-- Seed MECHANICAL : Engine Oil, Hydraulic Oil, Coolant, Fuel Leaks, Hydraulic Leaks, Tyres / Tracks, Steering, Cooling System

create table inspection_expectation (       -- ce qui DOIT être inspecté (généré par job)
  id uuid primary key, equipment_id uuid not null references equipment(id),
  site_id uuid not null, shift_id uuid not null, report_date date not null,
  window_start timestamptz not null, window_end timestamptz not null,
  state text not null default 'NOT_STARTED' check (state in
   ('NOT_STARTED','IN_PROGRESS','COMPLETED','COMPLETED_WITH_DEFECTS','CRITICAL_FAILURE','MISSING')),
  inspection_id uuid, missing_marked_at timestamptz,
  unique (equipment_id, shift_id, report_date));

create table inspection_draft (             -- jamais compté comme inspection
  id uuid primary key, expectation_id uuid references inspection_expectation(id),
  equipment_id uuid not null, user_id uuid not null, payload jsonb not null,
  updated_at timestamptz not null default now(), unique (equipment_id, user_id, expectation_id));

create table inspection (                   -- IMMUABLE
  id uuid primary key, client_uuid uuid not null unique,         -- idempotence hors-ligne
  expectation_id uuid references inspection_expectation(id),
  equipment_id uuid not null, site_id uuid not null, tunnel_id uuid, shift_id uuid not null, report_date date not null,
  operator_id uuid, operator_eid text, inspector_id uuid not null, inspector_eid text not null, supervisor_id uuid,
  engine_hours_start numeric(10,1), engine_hours_end numeric(10,1),   -- null = Not Recorded
  fuel_start_percent numeric(5,2), fuel_added_litres numeric(8,2),    -- unités explicites
  result text not null check (result in ('COMPLETED','COMPLETED_WITH_DEFECTS','CRITICAL_FAILURE')),
  submitted_late boolean not null default false, hours_warnings text[] not null default '{}',
  captured_at timestamptz not null, started_at timestamptz not null,
  submitted_at timestamptz not null default now(),                    -- horodatage serveur, fait foi
  attestation jsonb not null);  -- nom, EID, méthode, empreinte, signature dessinée optionnelle

create table inspection_answer (            -- IMMUABLE ; libellé figé au moment de la soumission
  inspection_id uuid not null references inspection(id), checklist_item_id uuid not null,
  category text not null, label_snapshot text not null,
  answer text not null check (answer in ('PASS','FAIL','NA')), na_reason text, defect_id uuid,
  primary key (inspection_id, checklist_item_id));

create table defect (
  id uuid primary key, equipment_id uuid not null, inspection_id uuid, checklist_item_id uuid,
  category text not null, description text not null,
  severity text not null check (severity in ('MINOR','MAJOR','CRITICAL')), immediate_action text,
  state text not null default 'OPEN' check (state in ('OPEN','IN_REPAIR','RESOLVED','CLOSED')),
  breakdown_id uuid, reported_by uuid not null, reported_at timestamptz not null,
  resolved_at timestamptz, resolved_by uuid, version bigint not null default 0);

create table inspection_correction (
  id uuid primary key, original_inspection_id uuid not null references inspection(id),
  changes jsonb not null,                   -- [{field, old, new}]
  reason text not null, requested_by uuid not null, requested_at timestamptz not null,
  decision text check (decision in ('APPROVED','REJECTED')), decided_by uuid, decided_at timestamptz, decision_comment text);

create table photo (
  id uuid primary key, storage_key text not null unique, sha256 text not null, mime text not null,
  size_bytes int not null, uploader_id uuid not null, uploaded_at timestamptz not null default now(),
  site_id uuid not null,                    -- pour l'autorisation d'accès
  target_type text not null check (target_type in ('EQUIPMENT','INSPECTION_ANSWER','DEFECT','BREAKDOWN','MAINTENANCE','STATUS_EVENT')),
  target_id uuid not null, kind text not null default 'EVIDENCE' check (kind in ('EVIDENCE','BEFORE','AFTER')));
```

### 4.5 Pannes, maintenance, heures moteur

```sql
create table breakdown (
  id uuid primary key, equipment_id uuid not null references equipment(id),
  category text not null, problem text not null, site_id uuid not null, tunnel_id uuid, shift_id uuid,
  reported_by uuid not null, reported_at timestamptz not null,
  breakdown_start timestamptz not null, repair_start timestamptz, repair_end timestamptz,
  state text not null check (state in ('REPORTED','DIAGNOSING','AWAITING_PARTS','UNDER_REPAIR','RESOLVED','CLOSED')),
  action_taken text, parts_required text, assigned_technician_id uuid, remarks text, version bigint not null default 0,
  check (repair_start is null or repair_start >= breakdown_start),
  check (repair_end is null or repair_end >= coalesce(repair_start, breakdown_start)));
-- anti-doublon : une seule panne active par équipement
create unique index uq_breakdown_active on breakdown (equipment_id) where state not in ('RESOLVED','CLOSED');

create table service_type (id uuid primary key, code text unique not null, name text not null,
  hours_based boolean not null, satisfies_type_ids uuid[] not null default '{}'); -- 1000h satisfait 250h et 500h
create table service_interval (id uuid primary key, service_type_id uuid not null, equipment_type_id uuid, equipment_id uuid,
  interval_hours int not null check (interval_hours > 0), due_soon_threshold_hours int not null default 50);

create table maintenance_record (
  id uuid primary key, equipment_id uuid not null, service_type_id uuid not null, site_id uuid not null, tunnel_id uuid,
  performed_at timestamptz not null, engine_hours numeric(10,1), work_performed text not null, cause text,
  technician_id uuid not null, supervisor_id uuid, breakdown_id uuid, replaced_components jsonb not null default '[]',
  downtime_minutes int, labour_cost bigint not null default 0, parts_cost bigint not null default 0,
  total_cost bigint generated always as (labour_cost + parts_cost) stored, currency char(3) not null,
  verification_status text not null default 'PENDING_VERIFICATION' check (verification_status in
   ('DRAFT','PENDING_VERIFICATION','VERIFIED','REJECTED','CORRECTION_REQUESTED')),
  verified_by uuid, verified_at timestamptz, verification_comment text, notes text, version bigint not null default 0);

create table engine_hour_reading (          -- IMMUABLE
  id uuid primary key, equipment_id uuid not null, reading numeric(10,1) not null check (reading >= 0),
  read_at timestamptz not null, source text not null check (source in ('INSPECTION_START','INSPECTION_END','METER_RECORD','MAINTENANCE')),
  source_id uuid not null, quality text not null check (quality in ('VALID','SUSPICIOUS','REJECTED')), corrects_id uuid);

create table working_hours_entry (          -- une entrée par inspection : jamais sommer les relevés bruts
  inspection_id uuid primary key, equipment_id uuid not null, site_id uuid not null, shift_id uuid not null,
  report_date date not null, hours_start numeric(10,1), hours_end numeric(10,1), recorded_hours numeric(8,1),
  status text not null check (status in ('VALID','MISSING_READING','SUSPICIOUS','OVERLAP','CORRECTED')));
```

### 4.6 Autres tables

| Table | Colonnes clés | Remarques |
| --- | --- | --- |
| `part` | part\_number (unique), description, category, unit, min\_stock | Catalogue |
| `part_stock` | part\_id, location, on\_hand | Mis à jour uniquement par `stock_movement` |
| `stock_movement` (immuable) | part\_id, location, qty signée, type RECEIPT/ISSUE/RETURN/ADJUSTMENT, request\_id, user\_id | Le stock est la somme des mouvements ; une demande n'en génère aucun |
| `part_request` / `part_request_line` | state REQUESTED/APPROVED/REJECTED/ISSUED/USED/RETURNED, equipment\_id, breakdown\_id ou maintenance\_id, qty demandée/émise/utilisée/retournée, unit\_cost | Workflow du § 34 |
| `technician` | eid unique, name, function, phone, site, tunnel/zone, shift, active, extra | Source : Engineering Maintenance Team.xlsx |
| `operator` / `operator_equipment` | eid unique, name, site, tunnel, shift, active ; affectations datées | Source : Operators by Site.xlsx |
| `notification` | user\_id, type, dedupe\_key (unique par user), payload, created\_at, read\_at | Anti-doublon par `dedupe_key` |
| `notification_rule` | type, roles, scope site, canal | Administrable |
| `import_profile` / `import_batch` / `import_row` | mapping colonnes, empreinte du fichier, statut ligne NEW/UPDATED/UNCHANGED/REJECTED, raison | Idempotence par clé métier + hash de ligne |
| `system_setting` | clé, valeur typée, portée | Fuseau, devise, seuils, politique d'approbation |
| `outbox_event` | type, payload, published\_at | Publication SSE/push après commit |
| `idempotency_record` | clé, utilisateur, empreinte requête, réponse | Rejeu sûr des POST |

Rapports et audit :

```sql
create table daily_report (                 -- rapport logique, unique par site/date/période
  id uuid primary key, report_ref text not null unique,
  site_id uuid not null, report_date date not null,
  period text not null check (period in ('DAY','RED','NIGHT','FULL_DAY')),
  prepared_by uuid not null, created_at timestamptz not null default now(),
  unique (site_id, report_date, period));

create table report_version (
  id uuid primary key, report_id uuid not null references daily_report(id), version_no int not null,
  state text not null check (state in ('DRAFT','SUBMITTED','RETURNED_FOR_CORRECTION','RESUBMITTED','APPROVED','SUPERSEDED')),
  narrative jsonb not null, snapshot jsonb not null, data_cutoff_at timestamptz not null,
  pdf_storage_key text, pdf_sha256 text, submitted_at timestamptz, approved_by uuid, approved_at timestamptz,
  approval_comment text, correction_reason text, unique (report_id, version_no));
-- Trigger : après submitted_at non nul, narrative/snapshot/data_cutoff_at/pdf_* sont figés.
-- Précise le § 4.1 : report_version est partiellement immuable, ses transitions sont tracées dans report_event (immuable).

create table audit_event (                  -- IMMUABLE, chaîné
  id bigint generated always as identity primary key, occurred_at timestamptz not null default now(),
  user_id uuid, user_eid text, role text, action text not null, entity_type text not null, entity_id text,
  site_id uuid, before jsonb, after jsonb, request_id text, ip inet, user_agent text,
  prev_hash bytea, hash bytea not null);     -- hash = SHA-256(prev_hash || contenu) : détection de falsification
```

## 5. Modèle de domaine Kotlin

### 5.1 Énumérations partagées (`shared`)

```kotlin
package rw.rutongo.mmems.shared.enums

enum class Role { MECHANIC, SUPERVISOR, MANAGER, GENERAL_MANAGER, ADMINISTRATOR }

/** Exactement trois statuts primaires. */
enum class OperationalStatus(val label: String, val priority: Int) {
    DOWN("Down", 0), UNDER_REPAIR("Under Repair", 1), OPERATIONAL("Operational", 2)
}

/** Condition de gestion DÉRIVÉE : ce n'est pas un statut stocké. */
enum class EquipmentCondition { DOWN, UNDER_REPAIR, OPERATIONAL_WITH_DEFECTS, OPERATIONAL, NOT_RECORDED }

enum class InspectionState { NOT_STARTED, IN_PROGRESS, COMPLETED, COMPLETED_WITH_DEFECTS, CRITICAL_FAILURE, MISSING }
enum class InspectionResult { COMPLETED, COMPLETED_WITH_DEFECTS, CRITICAL_FAILURE }
enum class Answer { PASS, FAIL, NA }
enum class Severity { MINOR, MAJOR, CRITICAL }
enum class ReportPeriod { DAY, RED, NIGHT, FULL_DAY }
enum class ReportState { DRAFT, SUBMITTED, RETURNED_FOR_CORRECTION, RESUBMITTED, APPROVED, SUPERSEDED }
enum class ServiceStatus { OK, DUE_SOON, OVERDUE, NO_VERIFIED_SERVICE, NOT_RECORDED }

@JvmInline value class EquipmentId(val value: String)
@JvmInline value class SiteId(val value: String)
```

### 5.2 Politique de statut (domaine pur)

```kotlin
package rw.rutongo.mmems.modules.status.domain

data class FieldError(val field: String, val message: String)

data class StatusChange(
    val equipmentId: EquipmentId, val newStatus: OperationalStatus,
    val problem: String?, val remarks: String?, val actionTaken: String?, val partsRequired: String?,
    val tunnelId: String?, val shiftId: String, val technicianIds: List<String>,
    val occurredAt: Instant, val expectedVersion: Long, val idempotencyKey: String,
)

object StatusPolicy {
    fun validate(c: StatusChange): List<FieldError> = buildList {
        when (c.newStatus) {
            OperationalStatus.DOWN ->
                if (c.problem.isNullOrBlank()) add(FieldError("problem", "A problem description is mandatory when equipment is DOWN."))
            OperationalStatus.UNDER_REPAIR -> {
                if (c.problem.isNullOrBlank()) add(FieldError("problem", "A problem description is mandatory when equipment is UNDER REPAIR."))
                if (c.actionTaken.isNullOrBlank()) add(FieldError("actionTaken", "Repair / work information is mandatory when equipment is UNDER REPAIR."))
            }
            OperationalStatus.OPERATIONAL -> Unit
        }
    }

    /** « Operational with defects » reste une condition, jamais un statut. */
    fun condition(status: OperationalStatus?, openDefects: Int): EquipmentCondition = when (status) {
        null -> EquipmentCondition.NOT_RECORDED
        OperationalStatus.DOWN -> EquipmentCondition.DOWN
        OperationalStatus.UNDER_REPAIR -> EquipmentCondition.UNDER_REPAIR
        OperationalStatus.OPERATIONAL ->
            if (openDefects > 0) EquipmentCondition.OPERATIONAL_WITH_DEFECTS else EquipmentCondition.OPERATIONAL
    }
}
```

## 6. Règles métier critiques (code de référence)

### 6.1 Résolution du shift, y compris après minuit

Règle : la **date de rapport d'un enregistrement est la date locale du début de son shift**. Une inspection faite à 02:00 pendant le Night Shift commencé la veille à 22:00 appartient donc au rapport de la veille. Les shifts doivent couvrir 24 h sans trou ni chevauchement (contrôle à la configuration).

```kotlin
data class ShiftWindow(val shiftId: String, val code: String, val start: LocalTime, val end: LocalTime) {
    val crossesMidnight get() = end <= start
}
data class ResolvedShift(val shiftId: String, val code: String, val reportDate: LocalDate, val startsAt: Instant, val endsAt: Instant)

class ShiftResolver(private val windows: List<ShiftWindow>) {
    fun resolve(at: Instant, tz: TimeZone): ResolvedShift {
        val today = at.toLocalDateTime(tz).date
        // On teste aujourd'hui et hier : un shift démarré hier peut couvrir l'instant courant.
        for (day in listOf(today, today.minus(1, DateTimeUnit.DAY))) {
            for (w in windows) {
                val s = LocalDateTime(day, w.start).toInstant(tz)
                val endDay = if (w.crossesMidnight) day.plus(1, DateTimeUnit.DAY) else day
                val e = LocalDateTime(endDay, w.end).toInstant(tz)
                if (at >= s && at < e) return ResolvedShift(w.shiftId, w.code, day, s, e)
            }
        }
        throw ShiftConfigurationException("No shift covers $at in $tz")
    }

    /** Intervalle [from, to) d'une période de rapport ; FULL_DAY = union des trois shifts débutant à cette date. */
    fun periodInterval(date: LocalDate, period: ReportPeriod, tz: TimeZone): Pair<Instant, Instant> { /* ... */ TODO() }
}
```

### 6.2 Enregistrement d'un statut : transaction, verrou, idempotence

```kotlin
class RecordStatusUseCase(
    private val tx: TransactionRunner, private val equipments: EquipmentRepository,
    private val events: StatusEventRepository, private val authz: SiteAuthorizer,
    private val audit: AuditPort, private val outbox: OutboxPort, private val clock: Clock,
) {
    suspend fun execute(actor: Principal, cmd: StatusChange): StatusEvent = tx.run {
        events.findByIdempotencyKey(cmd.idempotencyKey)?.let { return@run it }      // rejeu sûr
        val eq = equipments.lockForUpdate(cmd.equipmentId)                           // SELECT ... FOR UPDATE
        authz.require(actor, Permission.STATUS_UPDATE, eq.siteId)
        if (eq.version != cmd.expectedVersion) throw ConflictException(current = eq.snapshot())  // jamais d'écrasement silencieux
        StatusPolicy.validate(cmd).takeIf { it.isNotEmpty() }?.let { throw ValidationException(it) }

        val event = events.append(
            equipmentId = eq.id, seq = events.lastSeq(eq.id) + 1, previous = eq.currentStatus, new = cmd.newStatus,
            actor = actor, recordedAt = clock.now(), source = StatusSource.MANUAL, command = cmd,
        )
        // Projection : le statut courant = événement avec le plus grand occurred_at (arrivée tardive ne régresse pas l'état)
        equipments.applyStatusProjection(eq.id, events.latestByOccurredAt(eq.id))
        audit.record(actor, "EQUIPMENT_STATUS_CHANGED", "equipment", eq.id, before = eq.currentStatus, after = event.newStatus)
        outbox.publish(StatusChanged(eq.id, eq.siteId, event.newStatus))              // SSE + notifications après commit
        event
    }
}
```

### 6.3 Soumission d'une inspection

```kotlin
object InspectionValidator {
    fun validate(s: InspectionSubmission, items: List<ChecklistItem>, lastReading: Double?): ValidationOutcome {
        val errors = mutableListOf<FieldError>(); val warnings = mutableListOf<String>()
        val answers = s.answers.associateBy { it.itemId }
        for (item in items.filter { it.mandatory && it.active }) {
            val a = answers[item.id]
            if (a == null) { errors += FieldError("answers.${item.code}", "${item.label} must be answered."); continue }
            when (a.answer) {
                Answer.FAIL -> {
                    if (a.defect?.description.isNullOrBlank()) errors += FieldError("answers.${item.code}.defect", "Describe the defect.")
                    if ((item.safetyCritical || item.photoRequiredOnFail) && a.photoIds.isEmpty())
                        errors += FieldError("answers.${item.code}.photo", "A photo is required for this failure.")
                }
                Answer.NA -> if (item.naReasonRequired && a.naReason.isNullOrBlank())
                    errors += FieldError("answers.${item.code}.naReason", "Give a reason for N/A.")
                Answer.PASS -> Unit
            }
        }
        val start = s.engineHoursStart; val end = s.engineHoursEnd
        if (start == null) errors += FieldError("engineHoursStart", "Starting engine hours are required.")
        if (start != null && end != null && end < start) errors += FieldError("engineHoursEnd", "Ending hours cannot be lower than starting hours.")
        if (start != null && lastReading != null && start < lastReading) warnings += "START_BELOW_LAST_READING"
        if (start != null && end != null && end - start > 24.0) warnings += "WORKING_HOURS_ABOVE_24"
        if (s.attestation == null) errors += FieldError("attestation", "Signature is required before submission.")
        return ValidationOutcome(errors, warnings)
    }

    fun result(answers: List<AnswerWithSeverity>): InspectionResult = when {
        answers.any { it.answer == Answer.FAIL && it.severity == Severity.CRITICAL } -> InspectionResult.CRITICAL_FAILURE
        answers.any { it.answer == Answer.FAIL } -> InspectionResult.COMPLETED_WITH_DEFECTS
        else -> InspectionResult.COMPLETED
    }
}
```

Règle de statut automatique (table `status_rule`, administrable) : une défaillance `CRITICAL` sur un élément `safety_critical` déclenche soit une **recommandation** de passage à DOWN, soit un passage **automatique** (événement `source = SYSTEM_RULE`). Seule la permission `STATUS_OVERRIDE` permet de le contredire, avec motif obligatoire stocké dans `override_reason` et audité.

### 6.4 Calculs sans double comptage

```kotlin
data class Interval(val start: Instant, val end: Instant) { init { require(end >= start) } }

fun List<Interval>.union(): List<Interval> {
    if (isEmpty()) return emptyList()
    val sorted = sortedBy { it.start }
    val out = mutableListOf<Interval>()
    var acc = sorted.first()
    for (i in sorted.drop(1)) {
        acc = if (i.start <= acc.end) Interval(acc.start, maxOf(acc.end, i.end)) else { out += acc; i }
    }
    out += acc
    return out
}

fun List<Interval>.clip(to: Interval) = mapNotNull {
    val s = maxOf(it.start, to.start); val e = minOf(it.end, to.end); if (e > s) Interval(s, e) else null
}

/** Temps d'arrêt d'UN équipement sur une période : incidents superposés comptés une seule fois ; panne ouverte coupée au cutoff. */
fun downtimeMinutes(breakdowns: List<BreakdownSpan>, period: Interval, cutoff: Instant): Long =
    breakdowns.map { Interval(it.start, it.end ?: cutoff) }
        .clip(Interval(period.start, minOf(period.end, cutoff))).union()
        .sumOf { (it.end - it.start).inWholeMinutes }
```

- **Heures moteur** : la durée est la longueur de l'**union des intervalles sur l'axe du compteur** `[start, end]` de chaque inspection valide d'un même équipement. Deux inspections qui se recouvrent ne comptent donc qu'une fois ; l'entrée recouverte est marquée `OVERLAP`. On ne somme jamais des relevés bruts. Relevé manquant : `MISSING_READING`, affiché « Not Recorded ». Inspection manquante : « 0 recorded hours — inspection missing ».
- **Temps d'arrêt flotte** : somme des temps d'arrêt par équipement (chacun déjà dédupliqué). Les instants sont tous en UTC ; l'affichage seul est converti.
- **Heures de travail ≠ heures de main-d'œuvre** : aucun calcul de main-d'œuvre n'est déduit des heures moteur.

### 6.5 Échéance de service

```kotlin
data class ServiceDue(val current: Double?, val lastVerified: Double?, val nextDue: Double?, val remaining: Double?, val status: ServiceStatus)

fun serviceDue(current: Double?, lastVerified: Double?, interval: Int, dueSoon: Double = 50.0): ServiceDue = when {
    current == null -> ServiceDue(null, lastVerified, null, null, ServiceStatus.NOT_RECORDED)
    lastVerified == null -> ServiceDue(current, null, null, null, ServiceStatus.NO_VERIFIED_SERVICE)
    else -> {
        val next = lastVerified + interval; val remaining = next - current
        ServiceDue(current, lastVerified, next, remaining, when {
            remaining <= 0.0 -> ServiceStatus.OVERDUE
            remaining <= dueSoon -> ServiceStatus.DUE_SOON
            else -> ServiceStatus.OK
        })
    }
}
```

Seuls les services `VERIFIED` comptent. Un service de 1000 h réinitialise les compteurs 250 h et 500 h grâce à `service_type.satisfies_type_ids`. L'exemple du document (1 920 h, échéance 2 000 h, reste 80 h) devient un test unitaire.

### 6.6 Inspections manquantes (jobs planifiés)

- `GenerateExpectations` (toutes les 15 min) : crée les lignes `inspection_expectation` des shifts qui s'ouvrent, pour chaque équipement `ACTIVE` avec `requires_daily_inspection`. Idempotent (`ON CONFLICT DO NOTHING`).
- `MarkMissing` (toutes les 5 min) : `UPDATE ... SET state='MISSING' WHERE state IN ('NOT_STARTED','IN_PROGRESS') AND window_end < now() RETURNING ...`, puis événement outbox vers le superviseur du site, avec `dedupe_key = missing:{expectation_id}`.
- Filet de sécurité de 24 h : `hard_backstop_hours` ferme toute attente restée ouverte. Il complète les fenêtres par shift sans les remplacer.
- Un brouillon fait passer l'attente à `IN_PROGRESS` (information seulement). Une soumission tardive est acceptée avec `submitted_late = true` ; l'historique « Missing » reste visible.

### 6.7 Synchronisation hors-ligne (référencée en A10)

1. Chaque écriture mobile porte un identifiant client (`client_uuid` ou `Idempotency-Key`) : le rejeu après coupure ne crée jamais de doublon.
2. Inspections et événements de statut sont **append-only** : pas de fusion, pas de conflit d'écriture. L'ordre d'affichage suit `occurred_at`, la traçabilité suit `recorded_at`.
3. Le **statut courant** est l'événement valide au `occurred_at` le plus récent : un événement hors-ligne ancien qui arrive tard ne ramène pas l'équipement à un état périmé.
4. Fiches modifiables (équipement, panne, maintenance) : `If-Match` sur `version`. En cas de conflit, réponse `409` avec l'état courant ; l'interface propose de relire avant de renvoyer.
5. Dérive d'horloge : si l'écart entre `captured_at` et l'heure serveur dépasse 10 minutes, un avertissement est stocké.
6. Photos : compression côté client (≈ 1600 px), file `WorkManager` avec reprise, envoi `multipart` séparé rattaché au `client_uuid`. Une inspection n'est acceptée que si ses photos obligatoires sont référencées ; elles peuvent arriver juste après (état `PENDING_MEDIA` côté client).

### 6.8 Définitions des indicateurs du rapport

| Indicateur | Définition unique |
| --- | --- |
| Équipements | Équipements `ACTIVE` du site à l'instant `data_cutoff_at`, comptés une fois, classés par condition dérivée (§ 5.2) |
| Inspections | Lignes `inspection_expectation` de la période, par état ; une inspection n'est pas un équipement |
| Pannes signalées | Pannes distinctes dont `reported_at` est dans la période |
| Réparations terminées | Pannes dont `repair_end` est dans la période |
| Réparations en cours | Pannes non `RESOLVED` au cutoff |
| Maintenance réalisée | `maintenance_record` dont `performed_at` est dans la période, avec statut de vérification affiché |
| Maintenance due / en retard | Calcul § 6.5 au cutoff |
| Temps d'arrêt | § 6.4, borné à la période et au cutoff |
| Coûts | Visibles seulement avec `COST_VIEW` |
| Valeur absente | « Not Recorded », jamais 0 sauf mention explicite |

Le snapshot est construit dans **une seule transaction `REPEATABLE READ READ ONLY`**. Le PDF d'un brouillon est régénéré depuis un snapshot frais et filigrané DRAFT. Un rapport soumis ou approuvé utilise son snapshot figé ; le PDF stocké est vérifié par son empreinte SHA-256 à chaque téléchargement.

## 7. Catalogue d'API REST (`/api/v1`)

Conventions : JSON, erreurs `application/problem+json` avec `code` stable et `fieldErrors`, pagination par curseur, en-tête `Idempotency-Key` obligatoire sur les créations, `If-Match` sur les modifications versionnées, OpenAPI généré et publié en CI. Chaque route est protégée par permission **et** par portée de site.

| Module | Routes | Permission minimale |
| --- | --- | --- |
| Auth | `POST /auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/change-password`, `GET /me` | public / connecté |
| Organisation | `GET /sites`, `/tunnels`, `/shifts` ; `POST/PUT` sites, tunnels, shifts, schedules | lecture : connecté ; écriture : Administrator |
| Équipement | `GET /equipment`, `/equipment/{id}`, `/equipment/{id}/history?type=`, `/equipment/{id}/service-status` ; `POST/PUT /equipment` ; `POST /equipment/{id}/move` | EQUIPMENT\_VIEW ; gestion : Administrator |
| Statut | `POST /equipment/{id}/status`, `GET /equipment/{id}/status-events`, `GET /active-problems` | STATUS\_UPDATE / EQUIPMENT\_VIEW |
| Inspections | `GET /inspections/expected`, `/inspections/control`, `/inspections/{id}` ; `PUT /inspection-drafts/{equipmentId}` ; `POST /inspections` ; `POST /inspections/{id}/corrections` ; `POST /corrections/{id}/decision` ; `GET/POST/PUT /checklist-items` | INSPECTION\_SUBMIT ; décision : Supervisor, Manager ; checklist : Administrator |
| Défauts | `GET /defects`, `PATCH /defects/{id}` | Mechanic, Supervisor |
| Pannes | `POST /breakdowns`, `GET /breakdowns`, `POST /breakdowns/{id}/transition` | BREAKDOWN\_MANAGE |
| Maintenance | `POST /maintenance`, `GET /maintenance`, `POST /maintenance/{id}/verify`, `/reject`, `/request-correction`, `GET /service-schedule` | MAINTENANCE\_RECORD ; vérification : Supervisor |
| Heures | `GET /working-hours?site&equipment&from&to&shift&groupBy=` | EQUIPMENT\_VIEW |
| Pièces | `GET /parts`, `POST /part-requests`, `POST /part-requests/{id}/approve`, `/reject`, `/issue`, `/use`, `/return`, `GET /stock-movements` | demande : Mechanic ; approbation : Supervisor, Manager ; sortie : PARTS\_ISSUE |
| Personnel | `GET/POST/PUT /technicians`, `/operators`, `POST /assignments`, `/transfers` | lecture : Supervisor+ ; écriture : Administrator |
| Notifications | `GET /notifications`, `POST /notifications/{id}/read`, `/notifications/read-all`, `GET /events` (SSE) | connecté |
| Dashboards | `GET /dashboard/{mechanic\|supervisor\|manager\|executive}?site&from&to` | selon rôle |
| Rapports | `GET /reports/preview`, `POST /reports`, `PUT /reports/{id}/narrative`, `GET /reports/{id}/pdf?version=`, `POST /reports/{id}/submit`, `/approve`, `/return`, `/new-version`, `GET /reports` | REPORT\_PREPARE / REPORT\_APPROVE / REPORT\_VIEW\_APPROVED |
| Import | `POST /imports` (aperçu), `GET /imports/{id}`, `POST /imports/{id}/commit`, `GET /imports/{id}/rejections.csv` | IMPORT\_RUN (Administrator) |
| Export | `GET /exports/{dataset}.xlsx\|.csv` | EXPORT\_DATA |
| Photos | `POST /photos` (multipart), `GET /photos/{id}` (flux autorisé) | selon site de la photo |
| Administration | `GET/POST/PUT /users` (pas de DELETE), `GET /audit-events`, `GET/PUT /settings` | Administrator ; audit : Administrator (Manager configurable) |

## 8. Sécurité

### 8.1 Authentification et sessions

- Mots de passe hachés **Argon2id** (paramètres calibrés à ≈ 250 ms sur le serveur cible). Politique : 10 caractères minimum, liste de mots de passe courants refusée, changement obligatoire à la première connexion.
- Verrouillage progressif après 5 échecs ; limitation de débit `login` par IP et par nom d'utilisateur ; message d'erreur identique que le compte existe ou non.
- JWT d'accès 15 min, signature asymétrique (EdDSA/RS256) avec `kid` et rotation de clés. Les sites autorisés **ne sont pas dans le jeton** : ils sont résolus côté serveur (cache 60 s), donc une révocation s'applique aussitôt.
- Refresh token opaque, stocké haché, **rotatif avec détection de rejeu** (réutilisation = révocation de toute la famille). Durée : 30 jours sur Android (lié à l'appareil), 8 h sur le web, en cookie `HttpOnly; Secure; SameSite=Strict` avec en-tête anti-CSRF personnalisé.
- Création du premier administrateur : commande unique `java -jar app.jar bootstrap-admin` (ou tâche Gradle équivalente) qui lit le mot de passe initial dans l'environnement, refuse de s'exécuter si un administrateur existe, impose `must_change_password` et journalise l'événement.
- Attestation d'inspection : ressaisie du mot de passe ou d'un PIN à 6 chiffres distinct, limité en débit ; l'empreinte de l'attestation est stockée avec nom, EID et horodatage serveur.

### 8.2 Autorisation

Le document décrit précisément certains droits et reste silencieux sur d'autres (par exemple les droits d'écriture du Supervisor sur statuts et pannes). La matrice suivante est donc une **proposition à valider (A17)**.

| Capacité | Mechanic | Supervisor | Manager | General Manager | Administrator |
| --- | --- | --- | --- | --- | --- |
| Voir équipements (sites autorisés) | oui | oui | oui | oui | oui |
| Soumettre une inspection | oui | non | non | non | non |
| Déclarer défaut/panne, mettre à jour un statut, enregistrer une réparation, photos | oui | oui (A17) | non | non | non |
| Demander des pièces | oui | oui | non | non | non |
| Approuver les demandes de pièces | non | oui | oui | non | non |
| Surcharger une règle de statut automatique | non | oui | oui | non | non |
| Vérifier une maintenance | non | oui | non | non | non |
| Approuver une correction d'inspection | non | oui | oui | non | non |
| Préparer un rapport | non | oui | non | non | non |
| Approuver un rapport | non | selon politique (jamais l'auteur) | oui | non | non |
| Voir rapports approuvés / télécharger PDF | non | son site | oui | oui | non (configuration seulement) |
| Voir les coûts | non | configurable | oui | oui | non |
| Gérer utilisateurs, sites, config, import, checklists | non | non | non | non | oui |
| Consulter le journal d'audit | non | non | configurable | non | oui |

La matrice est la source unique : définie en code dans `RolePermissions`, testée exhaustivement (§ 13) et reprise telle quelle dans la documentation.

```kotlin
class SiteAuthorizer(private val access: UserSiteAccessRepository) {
    suspend fun require(p: Principal, permission: Permission, siteId: String) {
        if (permission !in RolePermissions.of(p.role)) throw ForbiddenException("MISSING_PERMISSION")
        if (!access.canAccess(p.userId, p.role, siteId)) throw ForbiddenException("SITE_NOT_AUTHORIZED")
    }
}
```

Toute requête de lecture est **filtrée par sites autorisés dans la requête SQL**, pas après coup, pour éviter les fuites par identifiant (IDOR) et les comptages incohérents.

### 8.3 Protection applicative (alignement OWASP ASVS)

| Menace | Mesure |
| --- | --- |
| Injection SQL | Exposed paramétré, aucune concaténation de SQL ; règle detekt interdisant l'exécution de SQL brut interpolé |
| Accès non autorisé aux objets (IDOR) | `SiteAuthorizer` sur chaque use case ; tests d'intégration par rôle × site |
| Affectation de masse | DTO explicites par opération, jamais d'entité exposée |
| Force brute / abus | Rate limiting Ktor (login strict, API par utilisateur) |
| XSS / clickjacking | CSP stricte sur le client web, `X-Content-Type-Options: nosniff`, `Referrer-Policy`, HSTS, `frame-ancestors 'none'` |
| CSRF | Jetons Bearer en en-tête ; endpoint de refresh web protégé par en-tête personnalisé + `SameSite=Strict` |
| CORS | Liste blanche d'origines, jamais `*` |
| Rejeu / doublons | `Idempotency-Key` + contraintes uniques |
| Falsification de l'historique | Triggers d'immutabilité, rôle SQL applicatif sans `UPDATE/DELETE` sur tables immuables, chaîne de hachage de l'audit |
| Fuite d'information | Journaux sans mot de passe ni jeton ; erreurs génériques côté client ; `X-Request-Id` pour la corrélation |
| Chaîne d'approvisionnement | OWASP Dependency-Check, Renovate, versions figées, image Docker minimale non-root |

### 8.4 Fichiers et photos

- Types JPG/JPEG/PNG vérifiés par **octets magiques** (pas seulement l'extension), taille maximale 10 Mo, décodage et ré-encodage via `ImageIO` pour neutraliser les contenus piégés, suppression des métadonnées EXIF sensibles (GPS) en conservant l'orientation.
- Clés de stockage aléatoires, bucket privé, aucune URL publique : lecture via `GET /photos/{id}` après contrôle de site, avec `Content-Disposition` et `nosniff`.
- Analyse antivirus ClamAV optionnelle, activable par configuration.

### 8.5 Audit, secrets, données

- Événements audités : connexions, changements de rôle/permission, statuts, soumissions et corrections d'inspection, vérifications, mouvements de stock, approbations de rapports, téléchargements de rapports, imports, exports, actions d'administration. Chaque événement porte utilisateur, action, référence, avant/après, horodatage, `request_id`, IP.
- Deux rôles PostgreSQL : `mmems_migrator` (DDL, utilisé par Flyway) et `mmems_app` (DML restreint). Aucun secret dans le code client ni dans le dépôt ; configuration par variables d'environnement ou gestionnaire de secrets ; TLS partout ; sauvegardes chiffrées.
- Données personnelles minimales (téléphone, EID) ; politique de rétention des rapports configurable ; exports tracés.

## 9. Import Excel

### 9.1 Principe en trois temps

1. **Téléversement** : le fichier est stocké, son empreinte SHA-256 est enregistrée, le profil de mapping est choisi (Assets, Technicians, Operators).
2. **Aperçu (dry-run)** : analyse en flux (Apache POI en mode événementiel), validation ligne à ligne, classement `NEW`, `UPDATED`, `UNCHANGED` ou `REJECTED` avec la raison. **Aucune écriture métier.**
3. **Validation** : l'Administrator confirme. Le commit s'exécute dans une seule transaction ; en cas d'échec technique, tout est annulé. Les lignes `REJECTED` sont exclues par construction et restent consultables (`rejections.csv`).

### 9.2 Règles

| Règle | Détail |
| --- | --- |
| Clés métier | Équipement : `asset_id` ; technicien : `eid` ; opérateur : `employee_id` |
| Idempotence | Hash normalisé de chaque ligne ; réimporter le même fichier donne 100 % `UNCHANGED` et zéro écriture |
| Mises à jour | Les lignes `UPDATED` ne sont appliquées que si l'Administrator coche « Apply updates ». Les champs de statut opérationnel ne sont **jamais** écrasés par un import |
| Colonnes inconnues | Conservées dans `extra` (jsonb), rien n'est perdu |
| Données absentes | Laissées nulles : aucune valeur inventée |
| Types d'équipement | Les types du fichier sont préservés. Un type inconnu est listé dans l'aperçu (« N nouveaux types ») et créé seulement après confirmation |
| Sites | Résolution insensible à la casse et aux espaces. Alias `Sanza` → `Sanzari` avec avertissement ; site introuvable = ligne rejetée, pas de création silencieuse |
| Ordre | Types, puis équipements, puis techniciens, puis opérateurs et affectations |
| Sécurité | Taille max. 20 Mo, extensions `.xlsx` uniquement, formules lues en valeur mise en cache, normalisation Unicode et `trim` |
| Audit | Un événement par import (compteurs, empreinte du fichier, utilisateur) |

```kotlin
class ImportEngine(private val repo: ImportTargetRepository, private val validator: RowValidator) {
    fun preview(file: SourceFile, profile: ImportProfile): ImportPreview {
        val rows = ExcelStreamReader(file).rows(profile.sheet)
        val results = rows.mapIndexed { idx, raw ->
            val mapped = profile.map(raw)                      // colonnes -> champs ; le reste va dans extra
            val errors = validator.validate(profile.kind, mapped)
            val existing = if (errors.isEmpty()) repo.findByKey(profile.kind, mapped.businessKey) else null
            val status = when {
                errors.isNotEmpty() -> RowStatus.REJECTED
                existing == null -> RowStatus.NEW
                existing.rowHash == mapped.normalizedHash() -> RowStatus.UNCHANGED
                else -> RowStatus.UPDATED
            }
            ImportRowResult(idx + 2, status, mapped, errors)    // +2 : ligne Excel humaine (en-tête = 1)
        }
        return ImportPreview(file.sha256, results)
    }
}
```

## 10. Rapport quotidien et génération du PDF

### 10.1 Chaîne de production

```
ReportSnapshotBuilder (transaction REPEATABLE READ, lecture seule)
  -> ReportViewModel (données + narratif + états « Not Recorded »)
  -> DailyReportPdfRenderer (OpenPDF, polices TTF embarquées)
  -> BlobStore (clé aléatoire) + SHA-256 -> report_version
```

Le rapport ne modifie jamais les tables opérationnelles : le module `report` n'a **aucun port d'écriture** vers les autres modules (vérifié par Konsist et par un test « tables sources inchangées »).

### 10.2 Mise en page

- A4, marges 15 mm, polices TTF embarquées (accents garantis), hiérarchie typographique sobre.
- En-tête : `RUTONGO MINES`, `MECHANICAL DEPARTMENT`, `DAILY ASSET STATUS REPORT`, `{SITE} • DAILY MAINTENANCE & ASSET STATUS` (A1). Bandeau : date, site, shift ou FULL DAY, superviseur et EID, techniciens.
- Résumé : cartes compactes Operational (vert), Under Repair (orange), Down (rouge), puis inspections, pannes, maintenance.
- Équipements triés : DOWN, UNDER REPAIR, OPERATIONAL WITH DEFECTS, puis OPERATIONAL (liste compacte si nombreux). Les défauts, inspections manquantes et pannes non résolues ne sont jamais masqués.
- Chaque bloc équipement est insécable (`keepTogether`) ; pagination automatique, jamais de réduction de police. Pied de page : `Page x / y`, horodatage de génération et version.
- Filigrane **DRAFT** sur les brouillons ; sceau « APPROVED par X le … » sur les rapports approuvés.
- Rendu des cas limites : « Not Recorded », « No breakdown reported », « Inspection missing — expected 06:00–08:00 », « Confirmed operational (inspected 06:42) ».

```kotlin
class DailyReportPdfRenderer(private val fonts: PdfFonts) {
    fun render(vm: ReportViewModel): ByteArray = ByteArrayOutputStream().use { out ->
        val doc = Document(PageSize.A4, 42f, 42f, 56f, 48f)
        val writer = PdfWriter.getInstance(doc, out)
        writer.pageEvent = HeaderFooterEvent(vm, fonts)       // pied de page, filigrane, pagination x / y
        doc.open()
        addLetterhead(doc, vm); addStatusSummary(doc, vm)
        addEquipmentBlocks(doc, vm.equipmentSortedByPriority()) // DOWN > UNDER_REPAIR > WITH_DEFECTS > OPERATIONAL
        addInspections(doc, vm); addBreakdownsAndRepairs(doc, vm); addPartsRequired(doc, vm)
        addMaintenance(doc, vm); addOutstanding(doc, vm); addNarrative(doc, vm); addApprovalBlock(doc, vm)
        doc.close(); out.toByteArray()
    }
}

fun reportFileName(site: String, date: LocalDate, period: ReportPeriod): String {
    fun clean(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').uppercase()
    val shift = if (period == ReportPeriod.FULL_DAY) "FULL_DAY" else period.name
    return "RUTONGO_MINES_DAILY_REPORT_${clean(site)}_${date}_$shift.pdf"
}
```

### 10.3 Workflow et versions

```
DRAFT -> SUBMITTED -> APPROVED
SUBMITTED -> RETURNED_FOR_CORRECTION -> RESUBMITTED -> APPROVED
APPROVED -> (correction autorisée, motif obligatoire) -> nouvelle version DRAFT ; l'ancienne passe SUPERSEDED, jamais écrasée
```

À la soumission : snapshot, narratif et PDF sont figés ; le PDF est généré, haché et stocké. À l'approbation : l'approbateur ne peut pas être l'auteur, le PDF reçoit le sceau d'approbation (nouveau fichier lié à la même version, empreinte mise à jour dans `report_event`). Chaque génération, soumission, retour, approbation et téléchargement est audité.

## 11. Notifications, temps réel et tâches planifiées

| Notification | Destinataires | Clé anti-doublon |
| --- | --- | --- |
| Inspection manquante | Supervisors du site | `missing:{expectation_id}` |
| Défaut critique | Supervisors du site, Managers | `critical:{defect_id}` |
| Équipement DOWN / nouvelle panne | Supervisors du site, Managers | `down:{event_id}` |
| Réparation nécessitant attention | Supervisor du site, technicien assigné | `repair:{breakdown_id}:{state}` |
| Demande de pièces | Supervisors, Managers | `partreq:{request_id}` |
| Stock bas | Rôles ayant PARTS\_ISSUE | `lowstock:{part_id}:{date}` |
| Service bientôt dû / en retard | Supervisor du site, Managers | `service:{equipment}:{type}:{status}` |
| Maintenance à vérifier | Supervisor du site | `verify:{maintenance_id}` |
| Rapport à approuver / retourné | Approbateurs / auteur | `report:{id}:{version}:{state}` |

Chaîne : écriture métier dans une transaction, puis ligne `outbox_event` dans la **même** transaction, puis un dispatcher (`FOR UPDATE SKIP LOCKED`) crée les `notification`, pousse sur le hub SSE et envoie FCM. Aucun événement perdu si le serveur redémarre.

- **SSE** (`GET /events`) : filtré par sites autorisés, `Last-Event-ID` pour la reprise, battement de 20 s. Si le flux est indisponible, le client bascule sur `GET /notifications?since=`.
- **Tâches planifiées** (db-scheduler) : `GenerateExpectations`, `MarkMissing`, `ServiceDueScan` (horaire), `LowStockScan`, `TokenCleanup`, `AuditChainVerify` (quotidien), `BackupVerification`.

## 12. Clients Android et Web

### 12.1 Principes

- **Un seul langage, une seule logique** : DTO, validations (`validation/`), `ShiftResolver` et calculs d'affichage sont dans `shared` et réutilisés tels quels. Le client ne fait aucune règle métier propre : il valide pour l'ergonomie, le serveur décide.
- **Android offline-first** (mécaniciens, superviseurs) : SQLDelight comme cache local, `WorkManager` pour la file d'envoi, CameraX pour les photos. L'écran lit toujours la base locale ; la synchronisation met la base à jour.
- **Web Compose Wasm** (superviseurs au bureau, managers, direction, administration) : en ligne uniquement, pas de file locale.
- Architecture d'écran : MVI (`State`, `Intent`, `Effect`), un `ViewModel` par écran, navigation filtrée par rôle (jamais d'élément de menu non autorisé).
- **Design system** : cibles tactiles de 56 dp minimum (gants), contraste élevé lisible en plein soleil, statuts toujours exprimés par **couleur + icône + texte** (pas par la couleur seule), libellés anglais courts, menus déroulants plutôt que saisie libre. Les écrans seront alignés sur vos maquettes Paper dès réception des exports.

### 12.2 Navigation par rôle (reprise du document)

| Rôle | Onglets |
| --- | --- |
| Mechanic | Home, My Inspections, Equipment, My Tasks, More |
| Supervisor | Dashboard, Inspections, Active Problems, Daily Site Report, Maintenance, More |
| Manager / General Manager | Dashboard, Sites, Equipment, Reports, More |
| Administrator | Dashboard, Operations, People, Configuration, More |

### 12.3 Assistant d'inspection (machine d'états)

```kotlin
sealed interface InspectionStep {
    data object Context : InspectionStep        // site, tunnel, équipement, opérateur, shift (pré-remplis)
    data object StartHours : InspectionStep     // heures moteur départ, carburant (%)
    data object Safety : InspectionStep         // 7 éléments, PASS / FAIL / N/A
    data object Mechanical : InspectionStep     // 8 éléments
    data object Defects : InspectionStep        // catégorie, description, gravité, action immédiate
    data object Photos : InspectionStep
    data object EndHours : InspectionStep       // heures fin, carburant ajouté (L)
    data object Review : InspectionStep
    data object Sign : InspectionStep
}

data class WizardState(
    val step: InspectionStep, val draft: InspectionDraft, val errors: Map<String, String>,
    val submitting: Boolean, val offlineQueued: Boolean,
)
```

- Le brouillon est écrit en base locale à chaque changement d'étape (jamais compté comme inspection).
- Le bouton **Submit** est désactivé tant que `InspectionValidator` (partagé) retourne des erreurs, et inactif pendant l'envoi (anti double soumission) ; le `client_uuid` est généré à l'ouverture du formulaire.
- Sans réseau : l'inspection passe en état local `QUEUED`, visible dans « My Inspections » avec l'indicateur « waiting to sync ». Le statut serveur (`Completed`, `Missing`...) n'est affiché que lorsqu'il est confirmé.

### 12.4 Moteur de synchronisation

```kotlin
class SyncWorker(ctx: Context, params: WorkerParameters, private val outbox: LocalOutbox, private val api: MmemsApi) :
    CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        for (op in outbox.pendingInOrder()) {
            when (val r = api.send(op)) {                         // Idempotency-Key = op.clientUuid
                is ApiResult.Ok -> outbox.markDone(op.id, r.serverVersion)
                is ApiResult.Conflict -> outbox.markConflict(op.id, r.current)   // l'utilisateur tranche
                is ApiResult.Invalid -> outbox.markRejected(op.id, r.fieldErrors) // jamais réessayé en boucle
                is ApiResult.Retryable -> return Result.retry()    // backoff exponentiel
            }
        }
        return Result.success()
    }
}
```

Données de référence (équipements, checklists, shifts) téléchargées en différentiel (`updatedSince`) et chiffrées au repos sur l'appareil (SQLCipher ou chiffrement Android Keystore). Déconnexion ou révocation : purge des données locales après envoi des opérations en attente (avec avertissement si elles ne sont pas synchronisées).

## 13. Stratégie de tests

### 13.1 Pyramide et outils

| Niveau | Outils | Cible |
| --- | --- | --- |
| Unitaire (domaine pur) | Kotest, tests basés sur propriétés | `ShiftResolver`, `union`/`downtime`, `serviceDue`, `InspectionValidator`, `StatusPolicy`, nom de fichier |
| Intégration persistance | Testcontainers PostgreSQL + Flyway | Migrations, contraintes, triggers d'immutabilité, concurrence (`FOR UPDATE`), index partiels |
| API / contrat | Ktor `testApplication`, schéma OpenAPI | Chaque route × chaque rôle × site autorisé / non autorisé |
| Sécurité | Tests dédiés + OWASP ZAP en CI | IDOR, élévation de privilège, rejeu de jeton, bruteforce, fichiers piégés |
| PDF | PDFBox (extraction de texte, nombre de pages) + rendu image de référence | Contenu, pagination, filigrane, nom de fichier |
| Import | Fichiers Excel de test (propres, sales, doublons) | Idempotence, rejets, alias de site |
| Architecture | Konsist | Dépendances entre couches et modules, `report` sans écriture |
| Android | Tests instrumentés + Robolectric | Parcours d'inspection, file hors-ligne, reprise après coupure |
| Web | Tests d'interface automatisés (Playwright) | Rapport quotidien, historique, administration |
| Performance | k6 | 1 000 équipements, 100 utilisateurs simultanés, PDF de 150 équipements |
| Mutation (modules critiques) | Pitest | Domaine statut, inspection, calculs |

Seuils de couverture (Kover, bloquants en CI) : domaine ≥ 90 %, application ≥ 80 %, global ≥ 75 %.

### 13.2 Exemples de tests de référence

```kotlin
class ServiceDueTest : FunSpec({
    test("exemple du document : 1920 h, dernier service vérifié à 1750 h, intervalle 250 h") {
        val r = serviceDue(current = 1920.0, lastVerified = 1750.0, interval = 250)
        r.nextDue shouldBe 2000.0; r.remaining shouldBe 80.0; r.status shouldBe ServiceStatus.OK
    }
    test("SERVICE DUE SOON à 50 h ou moins, OVERDUE à l'échéance") {
        serviceDue(1960.0, 1750.0, 250).status shouldBe ServiceStatus.DUE_SOON
        serviceDue(2000.0, 1750.0, 250).status shouldBe ServiceStatus.OVERDUE
    }
    test("sans service vérifié, jamais de statut inventé") {
        serviceDue(1920.0, null, 250).status shouldBe ServiceStatus.NO_VERIFIED_SERVICE
        serviceDue(null, 1750.0, 250).status shouldBe ServiceStatus.NOT_RECORDED
    }
})

class ShiftResolverTest : FunSpec({
    val tz = TimeZone.of("Africa/Kigali")
    // bornes d'exemple : à remplacer par la configuration réelle
    val resolver = ShiftResolver(listOf(
        ShiftWindow("d", "DAY", LocalTime(6, 0), LocalTime(14, 0)),
        ShiftWindow("r", "RED", LocalTime(14, 0), LocalTime(22, 0)),
        ShiftWindow("n", "NIGHT", LocalTime(22, 0), LocalTime(6, 0)),
    ))
    test("02:00 appartient au Night Shift de la veille") {
        val at = LocalDateTime(2026, 11, 5, 2, 0).toInstant(tz)
        resolver.resolve(at, tz).let { it.code shouldBe "NIGHT"; it.reportDate shouldBe LocalDate(2026, 11, 4) }
    }
})

class ImmutabilityTest : IntegrationTest() {
    @Test fun `un evenement de statut ne peut ni etre modifie ni supprime`() {
        val id = seedStatusEvent()
        shouldThrow<ExposedSQLException> { sql("update equipment_status_event set new_status='OPERATIONAL' where id = ?", id) }
        shouldThrow<ExposedSQLException> { sql("delete from equipment_status_event where id = ?", id) }
    }
}

class AuthorizationMatrixTest : ApiTest() {
    @ParameterizedTest @MethodSource("roleSiteRouteCases")
    fun `chaque role et chaque site renvoient le code attendu`(case: AuthzCase) =
        testApp { client.request(case.route) { bearer(tokenFor(case.role, case.sites)) }.status shouldBe case.expected }
}
```

### 13.3 Traçabilité des 35 tests d'acceptation

| Tests du document | Couverture automatisée | Sprint |
| --- | --- | --- |
| 1 – 3 (connexion, rôles, accès aux sites) | `AuthFlowTest`, `AuthorizationMatrixTest`, `SiteScopeIdorTest` | 1 |
| 4 (import, pas de doublon d'Asset ID) | `ImportIdempotencyTest`, contrainte `unique(asset_id)` | 2 |
| 5 (persistance après redémarrage) | `RestartPersistenceTest` (Testcontainers, redémarrage de l'application) | 3 |
| 6 (historique de statut) | `StatusHistoryTest`, `ImmutabilityTest` | 3 |
| 7 – 8 (champs obligatoires, défauts créés) | `InspectionValidatorTest`, `InspectionSubmitIT` | 4 |
| 9 (heures moteur invalides) | `EngineHoursTest` (propriétés) | 4 |
| 10 (inspections manquantes) | `MarkMissingJobIT`, `ShiftResolverTest` | 4 |
| 11 (photos sécurisées) | `PhotoUploadSecurityTest`, `PhotoAccessIT` | 5 |
| 12 (pannes, réparations, maintenance, pièces) | `BreakdownFlowIT`, `MaintenanceFlowIT`, `PartsWorkflowIT` | 6 – 8 |
| 13 (échéances de service) | `ServiceDueTest`, `ServiceScheduleIT` | 7 |
| 14 (totaux de tableaux de bord) | `DashboardConsistencyIT` (comparaison avec requêtes SQL directes) | 10 |
| 15 (audit) | `AuditCoverageTest` : chaque cas d'usage sensible produit un événement, chaîne vérifiée | 1 – 12 |
| 16 – 21 (rapport, filtres, totaux, remarques) | `ReportSnapshotIT`, `ReportMetricsTest`, `ReportUiTest` | 9 |
| 22 – 25 (PDF, pagination, brouillon/approuvé) | `PdfRenderTest` (texte, pages, filigrane, nom de fichier) | 9 |
| 26 – 28 (historique, versions, accès) | `ReportHistoryIT`, `ReportVersioningIT`, `ReportAuthzTest` | 9 |
| 29 (rapport sans effet de bord) | `ReportReadOnlyTest` : empreinte des tables sources avant / après génération | 9 |
| 30 (aucune donnée inventée) | `NotRecordedRenderingTest` : chaque champ absent affiche « Not Recorded » | 9 |
| 31 – 33 (usage téléphone, supervision, rapport en quelques minutes) | Tests instrumentés Android + **recette utilisateur chronométrée** | 5, 9, 12 |
| 34 (comparaison de sites) | Tableau de bord Manager + test d'interface web | 11 |
| 35 (boutons, filtres, formulaires) | Checklist de recette exhaustive générée depuis OpenAPI et la carte des écrans | 12 |

## 14. Intégration continue, déploiement et exploitation

### 14.1 Pipeline CI/CD (GitHub Actions)

1. **Sur chaque PR** : `ktlintCheck`, `detekt`, tests unitaires, Konsist, tests d'intégration (Testcontainers), Kover (seuils), build de l'image Docker, OWASP Dependency-Check, scan de secrets.
2. **Sur fusion dans `main`** : publication de l'image, déploiement automatique sur `staging`, migrations Flyway, tests de fumée, ZAP en mode baseline.
3. **Sur étiquette de version** : déploiement en production après approbation manuelle, avec sauvegarde préalable de la base.

Migrations **avant-compatibles** (expand / contract) : une migration ne casse jamais la version précédente de l'application, ce qui permet un retour arrière sans restauration.

### 14.2 Environnements et configuration

| Variable | Rôle |
| --- | --- |
| `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` | Connexion PostgreSQL (rôle `mmems_app`) |
| `MIGRATOR_USER`, `MIGRATOR_PASSWORD` | Rôle de migration Flyway |
| `JWT_PRIVATE_KEY_PEM`, `JWT_KEY_ID` | Signature des jetons |
| `BLOB_STORE`, `S3_ENDPOINT`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` | Stockage des fichiers |
| `APP_TIMEZONE`, `APP_CURRENCY` | Valeurs par défaut (A7, A8) |
| `CORS_ALLOWED_ORIGINS` | Origines autorisées |
| `FCM_CREDENTIALS` | Notifications push |
| `BOOTSTRAP_ADMIN_USERNAME`, `BOOTSTRAP_ADMIN_PASSWORD`, `BOOTSTRAP_ADMIN_EID` | Premier administrateur, utilisés une seule fois |
| `LOG_LEVEL` | Niveau de journalisation |

```dockerfile
FROM gradle:8-jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle :server:installDist --no-daemon

FROM eclipse-temurin:21-jre
RUN useradd -r -u 10001 app
COPY --from=build /src/server/build/install/server /opt/server
USER app
EXPOSE 8080
ENTRYPOINT ["/opt/server/bin/server"]
```

La pile de production (`ops/docker-compose.yml`) comprend : application, PostgreSQL, MinIO, Caddy (TLS automatique). Les volumes de données sont séparés ; seuls les ports 80 et 443 sont exposés.

### 14.3 Sauvegardes, observabilité, exploitation

- **Base** : `pg_dump` quotidien chiffré + archivage WAL pour restauration à l'instant voulu. **Fichiers** : réplication MinIO ou copie quotidienne. Objectifs proposés : perte de données maximale 24 h (5 min avec WAL), reprise sous 4 h. **Exercice de restauration mensuel**, tracé dans un runbook.
- **Santé** : `/healthz` (processus) et `/readyz` (base, stockage, scheduler).
- **Métriques** Prometheus : latence, erreurs, file outbox, retard des jobs, inspections manquantes générées, échecs de synchronisation, durée de génération PDF. **Alertes** : erreurs 5xx, outbox bloquée, job en retard, espace disque, sauvegarde manquante, échec de `AuditChainVerify`.
- **Journaux** JSON corrélés par `X-Request-Id`, sans donnée sensible.
- **Documentation livrée** : guide d'installation, guide d'import, guide d'administration, runbooks (restauration, rotation de clés, incident), OpenAPI, guide utilisateur par rôle.

## 15. Plan de sprints

Hypothèses : sprints de 2 semaines, équipe de 4 personnes (2 backend, 1 mobile/web, 1 QA/DevOps à temps partiel), soit environ 26 semaines jusqu'à la mise en production d'un pilote. À recalibrer après validation du périmètre.

**Définition de prêt** : critères d'acceptation écrits, décisions A1–A17 tranchées pour le périmètre du sprint, maquettes disponibles. **Définition de terminé** : code revu, tests automatisés au vert, couverture respectée, migration testée, audit et autorisations vérifiés, documentation et OpenAPI à jour, démo validée.

| Sprint | Objectif | Livrables principaux | Critère de sortie |
| --- | --- | --- | --- |
| 0 | Fondations | Dépôt, Gradle, CI, Docker, Flyway de base, Koin, modèle d'erreurs, ports `Audit`/`Outbox`/`Clock`, triggers d'immutabilité, Konsist | Pipeline vert, base migrée, tests d'architecture actifs |
| 1 | Identité et sécurité | Connexion, JWT + refresh rotatif, RBAC et portée de site, `bootstrap-admin`, gestion des utilisateurs, limitation de débit, audit chaîné | Tests 1 – 3 ; matrice rôle × site automatisée |
| 2 | Organisation, import, référentiels | Sites, tunnels, shifts, types, moteur d'import (3 profils), techniciens, opérateurs, équipements, mouvements, infrastructure photo | Test 4 ; import idempotent démontré |
| 3 | Statut et historique | Événements de statut, projection, verrouillage optimiste, Active Problems, fiche équipement, SSE de base | Tests 5 – 6 |
| 4 | Inspections côté serveur | Checklists administrables, attentes, validation, soumission, corrections, contrôle quotidien, inspections manquantes, règle de statut automatique | Tests 7 – 10 |
| 5 | Application Android | Assistant d'inspection hors-ligne, caméra, file de synchronisation, brouillons, mises à jour de statut | Tests 11 et 31 ; parcours validé sur téléphones réels |
| 6 | Défauts, pannes, réparations | Gestion des pannes, calcul du temps d'arrêt, liens défauts-pannes, photos de panne | Test 12 (partie pannes) ; cas de superposition d'incidents |
| 7 | Maintenance et heures | Types de service, intervalles, planification, vérification superviseur, historique de service, heures de travail | Tests 9 et 13 |
| 8 | Pièces et personnel | Catalogue, demandes, approbation, sorties, retours, mouvements de stock, stock bas, affectations et transferts | Test 12 (pièces) ; stock jamais modifié par une simple demande |
| 9 | Rapport quotidien et PDF | Snapshot, narratif, aperçu, PDF, workflow et versions, historique, téléchargement autorisé | Tests 16 – 30 |
| 10 | Tableaux de bord et notifications | 4 tableaux de bord, règles de notification, push FCM, exports Excel/CSV | Test 14 ; totaux = requêtes SQL |
| 11 | Application Web | Tableaux de bord direction, rapports, historique, administration, import | Test 34 ; parcours Manager complet |
| 12 | Durcissement et pilote | Test d'intrusion, charge, exercice de restauration, recette chronométrée, formation, pilote sur un site | Tests 15, 31 – 33, 35 ; rapport d'acceptation signé |

Les lots utilisateurs sont déployés en **pilote sur un site** (par exemple Workshop) avant généralisation aux cinq autres sites.

## 16. Risques, limites et décisions à confirmer

### 16.1 Risques principaux

| Risque | Impact | Réponse |
| --- | --- | --- |
| Maturité de Compose Wasm | Poids, accessibilité du web | Plan B Kotlin/JS (§ 1) ; décision prise à la fin du Sprint 0 avec un prototype |
| Conflits hors-ligne et dérive d'horloge | Données incohérentes | Append-only, idempotence, statut courant par `occurred_at`, avertissement de dérive (§ 6.7) |
| Qualité des fichiers Excel | Rejets massifs, doublons | Aperçu obligatoire, alias, rapport de rejets, import répétable |
| Configuration erronée des shifts | Inspections mal attribuées, rapports faux | Validation de couverture 24 h à la configuration, tests de passage de minuit |
| Adoption par les mécaniciens | Inspections non faites | Formulaire court, pré-remplissage, recette chronométrée, pilote et retours rapides |
| Définitions d'indicateurs contestées | Chiffres différents entre écrans | Définition unique (§ 6.8) validée avant le Sprint 9 |
| Connectivité sous terre | Retards de synchronisation | Hors-ligne natif, indicateur d'état de synchronisation |

### 16.2 Limites de ce document

- Aucun code de ce document n'a été compilé ni exécuté ici. Les extraits sont des références d'implémentation à compléter et à tester ; aucune fonctionnalité n'est à considérer comme réalisée, ni le système comme prêt pour la production, avant les tests d'acceptation du § 13.3.
- Les versions des bibliothèques sont à figer au Sprint 0.
- Le lien Paper n'a pas pu être lu : le design n'est pas pris en compte.
- Les trois fichiers Excel n'ont pas été fournis : les profils de mapping seront finalisés sur les fichiers réels.

### 16.3 Décisions à confirmer avant le Sprint 0

1. Horaires exacts des shifts Day, Red et Night, et fenêtre d'inspection par site (A7, § 6.1).
2. Fuseau horaire (proposé : Africa/Kigali) et devise (A7, A8).
3. Unités du carburant (proposé : niveau en %, ajout en litres).
4. Politique d'approbation des rapports : Manager seul, ou Supervisor autre que l'auteur (§ 8.2).
5. Droits d'écriture du Supervisor sur statuts, pannes et défauts (A17).
6. Mode de la règle de statut automatique : recommandation ou passage automatique à DOWN (§ 6.3).
7. Hébergement : serveur local RutongoMines ou cloud ; système minimal Android visé.
8. Fichiers Excel réels et exports des maquettes Paper (PNG, PDF ou tokens de design).
9. Équipements dont l'inspection n'est pas requise, et règle d'exemption temporaire éventuelle.
10. Durée de rétention des rapports et des photos.

### 16.4 Suite proposée

Une fois ces points tranchés, le code se génère sprint par sprint, chaque livraison étant accompagnée de ses migrations, de ses tests et de sa documentation. Le Sprint 0 (squelette du dépôt, Gradle, CI, Docker, migrations de base et triggers d'immutabilité) est le point de départ logique.
