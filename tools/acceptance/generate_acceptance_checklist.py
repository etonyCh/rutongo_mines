#!/usr/bin/env python3
"""Generate the exhaustive Test 35 acceptance checklist.

The checklist is derived from two reviewed sources:
  * docs/openapi.yaml - every API operation and its contract.
  * tools/acceptance/screen-map.yaml - every web/Android screen and control.

The generator fails closed: an unreviewed OpenAPI operation, an unknown screen
source, a duplicate stable ID, or a stale generated document exits non-zero.
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

import yaml

METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"}
ROLES = {"MECHANIC", "SUPERVISOR", "MANAGER", "GENERAL_MANAGER", "ADMINISTRATOR"}
CONTROL_TYPES = {
    "action",
    "button",
    "display",
    "field",
    "filter",
    "form",
    "navigation",
    "offline",
    "state",
}
OPERATION_RE = re.compile(r"^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+(/\S+)$")
REPO_ROOT = Path(__file__).resolve().parents[2]


def load_yaml(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        value = yaml.safe_load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a YAML mapping")
    return value


def slug(value: str) -> str:
    return re.sub(r"[^A-Z0-9]+", "-", value.upper()).strip("-")


def operation_key(method: str, path: str) -> str:
    return f"{method.upper()} {path}"


def operation_id(key: str) -> str:
    method, path = key.split(" ", 1)
    return f"T35-API-{slug(method + ' ' + path)}"


def stable_id(platform: str, screen: str, item: str) -> str:
    screen_slug = slug(screen)
    platform_slug = slug(platform)
    if not screen_slug.startswith(f"{platform_slug}-"):
        screen_slug = f"{platform_slug}-{screen_slug}"
    return f"T35-{screen_slug}-{slug(item)}"


def resolve_local_ref(document: dict[str, Any], node: Any) -> Any:
    seen: set[str] = set()
    current = node
    while isinstance(current, dict) and "$ref" in current:
        ref = current["$ref"]
        if not isinstance(ref, str) or not ref.startswith("#/"):
            return current
        if ref in seen:
            return {}
        seen.add(ref)
        target: Any = document
        for part in ref[2:].split("/"):
            target = target[part.replace("~1", "/").replace("~0", "~")]
        current = target
    return current


def merged_schema(document: dict[str, Any], schema: Any) -> dict[str, Any]:
    schema = resolve_local_ref(document, schema)
    if not isinstance(schema, dict):
        return {}
    merged: dict[str, Any] = {}
    for item in schema.get("allOf", []):
        merged.update(merged_schema(document, item))
    merged.update({key: value for key, value in schema.items() if key != "allOf"})
    return merged


def schema_fields(document: dict[str, Any], schema: Any) -> tuple[list[str], set[str]]:
    schema = merged_schema(document, schema)
    properties = schema.get("properties", {})
    if not isinstance(properties, dict):
        return [], set()
    required = set(schema.get("required", []) or [])
    return list(properties.keys()), required


def collect_operations(document: dict[str, Any]) -> dict[str, dict[str, Any]]:
    paths = document.get("paths")
    if not isinstance(paths, dict):
        raise ValueError("OpenAPI document has no paths mapping")
    global_security = document.get("security", [])
    operations: dict[str, dict[str, Any]] = {}
    for path, path_item in paths.items():
        if not isinstance(path_item, dict):
            continue
        path_parameters = path_item.get("parameters", []) or []
        for method, op in path_item.items():
            method_upper = method.upper()
            if method_upper not in METHODS or not isinstance(op, dict):
                continue
            key = operation_key(method_upper, path)
            raw_parameters = list(path_parameters) + list(op.get("parameters", []) or [])
            parameters: list[dict[str, Any]] = []
            for raw in raw_parameters:
                parameter = resolve_local_ref(document, raw)
                if isinstance(parameter, dict):
                    parameters.append(parameter)
            content = (op.get("requestBody") or {}).get("content") or {}
            content_type = next(iter(content), None)
            body_fields: list[str] = []
            required_fields: set[str] = set()
            if content_type is not None:
                body_fields, required_fields = schema_fields(document, content[content_type].get("schema", {}))
            security = op.get("security", global_security)
            operations[key] = {
                "key": key,
                "id": operation_id(key),
                "method": method_upper,
                "path": path,
                "summary": op.get("summary") or "",
                "tags": op.get("tags") or [],
                "parameters": parameters,
                "body_content_type": content_type,
                "body_fields": body_fields,
                "required_fields": required_fields,
                "security": security or [],
                "responses": op.get("responses") or {},
            }
    return operations


def parameter_text(operation: dict[str, Any]) -> str:
    groups: dict[str, list[str]] = defaultdict(list)
    for parameter in operation["parameters"]:
        name = parameter.get("name")
        location = parameter.get("in")
        if not isinstance(name, str) or not isinstance(location, str):
            continue
        suffix = " (required)" if parameter.get("required") else ""
        groups[location].append(f"`{name}`{suffix}")
    if operation["body_fields"]:
        body = []
        for field in operation["body_fields"]:
            suffix = " (required)" if field in operation["required_fields"] else ""
            body.append(f"`{field}`{suffix}")
        groups["body"].append(f"{operation['body_content_type']}: " + ", ".join(body))
    if not groups:
        return "None."
    ordered = []
    for label in ("path", "query", "header", "body", "cookie"):
        if groups.get(label):
            ordered.append(f"{label}: " + ", ".join(groups[label]))
    return "; ".join(ordered) + "."


def response_text(operation: dict[str, Any]) -> str:
    responses = operation["responses"]
    success: list[str] = []
    errors: list[str] = []
    for status, response in responses.items():
        description = ""
        if isinstance(response, dict):
            description = response.get("description") or ""
        text = f"`{status}`"
        if description:
            text += f" {description}"
        if re.fullmatch(r"2\d\d", str(status)):
            success.append(text)
        else:
            errors.append(text)
    success_text = "; ".join(success) if success else "first documented 2xx response"
    error_text = "; ".join(errors) if errors else "documented error responses"
    parts = [f"success: {success_text}", f"errors: {error_text}"]
    header_names = {
        str(parameter.get("name", "")).lower()
        for parameter in operation["parameters"]
        if parameter.get("in") == "header"
    }
    if "if-match" in header_names:
        parts.append("concurrency: require and echo the current entity version with `If-Match`")
    if "idempotency-key" in header_names:
        parts.append("idempotency: repeat the same `Idempotency-Key` to verify no duplicate is created")
    return "; ".join(parts) + "."


def access_text(operation: dict[str, Any]) -> str:
    security = operation["security"]
    if not security:
        return "Public."
    schemes: set[str] = set()
    for requirement in security:
        if isinstance(requirement, dict):
            schemes.update(str(name) for name in requirement)
    return ("Authenticated: " + ", ".join(f"`{name}`" for name in sorted(schemes)) + ".") if schemes else "Authenticated."


def read_operation(value: Any, operations: dict[str, dict[str, Any]], where: str, errors: list[str]) -> str | None:
    if not isinstance(value, str):
        errors.append(f"{where}: operation must be a string")
        return None
    match = OPERATION_RE.fullmatch(value.strip())
    if not match:
        errors.append(f"{where}: operation '{value}' must look like 'GET /api/v1/path'")
        return None
    key = operation_key(match.group(1), match.group(2))
    if key not in operations:
        errors.append(f"{where}: unknown OpenAPI operation '{key}'")
        return None
    return key


def read_behaviors(document: dict[str, Any], errors: list[str]) -> dict[str, str]:
    raw = document.get("behaviors", {})
    if not isinstance(raw, dict):
        errors.append("behaviors must be a mapping")
        return {}
    behaviors: dict[str, str] = {}
    for key, value in raw.items():
        if not isinstance(key, str) or not isinstance(value, str) or not value.strip():
            errors.append(f"behaviors.{key}: must be a non-empty string")
            continue
        behaviors[key] = value.strip()
    return behaviors


def validate_map(
    document: dict[str, Any],
    operations: dict[str, dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]], dict[str, str], list[str], set[str], dict[str, list[str]]]:
    errors: list[str] = []
    if document.get("version") != 1:
        errors.append("version must be 1")
    behaviors = read_behaviors(document, errors)
    platforms = document.get("platforms")
    if not isinstance(platforms, dict) or not platforms:
        errors.append("platforms must be a non-empty mapping")
        platforms = {}
    for platform, meta in platforms.items():
        if not isinstance(meta, dict) or not isinstance(meta.get("title"), str):
            errors.append(f"platforms.{platform}: title is required")

    screens = document.get("screens")
    if not isinstance(screens, list):
        errors.append("screens must be a list")
        screens = []
    deferred = document.get("deferred", [])
    if not isinstance(deferred, list):
        errors.append("deferred must be a list")
        deferred = []
    backend_only = document.get("backend_only", [])
    if not isinstance(backend_only, list):
        errors.append("backend_only must be a list")
        backend_only = []

    global_checks = document.get("global_checks", [])
    if not isinstance(global_checks, list):
        errors.append("global_checks must be a list")
        global_checks = []

    mapped_operations: set[str] = set()
    mapping_by_operation: dict[str, list[str]] = defaultdict(list)
    screen_ids: set[str] = set()
    deferred_ids: set[str] = set()

    def check_states(states: Any, where: str) -> list[str]:
        if states is None:
            return []
        if not isinstance(states, list):
            errors.append(f"{where}: states must be a list")
            return []
        result = []
        for state in states:
            if not isinstance(state, str) or not state:
                errors.append(f"{where}: state must be a non-empty string")
            elif state not in behaviors:
                errors.append(f"{where}: unknown behavior '{state}'")
            else:
                result.append(state)
        return result

    def check_operations(values: Any, where: str) -> list[str]:
        if values is None:
            return []
        if not isinstance(values, list):
            errors.append(f"{where}: operations must be a list")
            return []
        result = []
        for value in values:
            key = read_operation(value, operations, where, errors)
            if key is not None:
                result.append(key)
        return result

    for index, screen in enumerate(screens):
        where = f"screens[{index}]"
        if not isinstance(screen, dict):
            errors.append(f"{where}: must be a mapping")
            continue
        screen_id = screen.get("id")
        platform = screen.get("platform")
        title = screen.get("title")
        source = screen.get("source")
        roles = screen.get("roles")
        entry = screen.get("entry")
        purpose = screen.get("purpose", "")
        if not isinstance(screen_id, str) or not screen_id:
            errors.append(f"{where}: id is required")
            screen_id = f"screen-{index}"
        elif screen_id in screen_ids:
            errors.append(f"{where}: duplicate screen id '{screen_id}'")
        screen_ids.add(screen_id)
        if platform not in platforms:
            errors.append(f"{where}: unknown platform '{platform}'")
        if not isinstance(title, str) or not title:
            errors.append(f"{where}: title is required")
        if not isinstance(source, str) or not source:
            errors.append(f"{where}: source is required")
        elif not (REPO_ROOT / source).is_file():
            errors.append(f"{where}: source does not exist: {source}")
        if not isinstance(roles, list) or not roles:
            errors.append(f"{where}: roles must be a non-empty list")
            roles = []
        for role in roles:
            if role not in ROLES:
                errors.append(f"{where}: unknown role '{role}'")
        if not isinstance(entry, str) or not entry:
            errors.append(f"{where}: entry is required")
        screen["_item_ids"] = set()
        for collection_name in ("checks", "controls"):
            collection = screen.get(collection_name, [])
            if collection is None:
                collection = []
            if not isinstance(collection, list):
                errors.append(f"{where}.{collection_name}: must be a list")
                continue
            for item_index, item in enumerate(collection):
                item_where = f"{where}.{collection_name}[{item_index}]"
                if not isinstance(item, dict):
                    errors.append(f"{item_where}: must be a mapping")
                    continue
                item_id = item.get("id")
                if not isinstance(item_id, str) or not item_id:
                    errors.append(f"{item_where}: id is required")
                    item_id = f"item-{item_index}"
                if item_id in screen["_item_ids"]:
                    errors.append(f"{item_where}: duplicate item id '{item_id}' in screen")
                screen["_item_ids"].add(item_id)
                expected = item.get("expected", "")
                states = check_states(item.get("states"), item_where)
                if not isinstance(expected, str):
                    errors.append(f"{item_where}: expected must be a string")
                    expected = ""
                if not expected.strip() and not states:
                    errors.append(f"{item_where}: expected or states is required")
                if collection_name == "controls":
                    label = item.get("label")
                    control_type = item.get("type")
                    if not isinstance(label, str) or not label:
                        errors.append(f"{item_where}: label is required for controls")
                    if control_type not in CONTROL_TYPES:
                        errors.append(f"{item_where}: unknown control type '{control_type}'")
                item_operations = check_operations(item.get("operations"), item_where)
                item["_operations"] = item_operations
                full_id = stable_id(platform if isinstance(platform, str) else "unknown", screen_id, item_id)
                for key in item_operations:
                    mapped_operations.add(key)
                    mapping_by_operation[key].append(full_id)
        if purpose is not None and not isinstance(purpose, str):
            errors.append(f"{where}: purpose must be a string")

    for index, item in enumerate(deferred):
        where = f"deferred[{index}]"
        if not isinstance(item, dict):
            errors.append(f"{where}: must be a mapping")
            continue
        item_id = item.get("id")
        source = item.get("source")
        reason = item.get("reason")
        roles = item.get("roles", [])
        if not isinstance(item_id, str) or not item_id:
            errors.append(f"{where}: id is required")
        elif item_id in deferred_ids or item_id in screen_ids:
            errors.append(f"{where}: duplicate deferred/screen id '{item_id}'")
        deferred_ids.add(str(item_id))
        if not isinstance(source, str) or not source:
            errors.append(f"{where}: source is required")
        elif not (REPO_ROOT / source).is_file():
            errors.append(f"{where}: source does not exist: {source}")
        if not isinstance(reason, str) or not reason:
            errors.append(f"{where}: reason is required")
        if not isinstance(roles, list) or not roles:
            errors.append(f"{where}: roles must be a non-empty list")
        else:
            for role in roles:
                if role not in ROLES:
                    errors.append(f"{where}: unknown role '{role}'")

    backend_operations: set[str] = set()
    for index, item in enumerate(backend_only):
        where = f"backend_only[{index}]"
        if not isinstance(item, dict):
            errors.append(f"{where}: must be a mapping")
            continue
        key = read_operation(item.get("operation"), operations, where, errors)
        reason = item.get("reason")
        if key is not None:
            if key in backend_operations:
                errors.append(f"{where}: duplicate backend-only operation '{key}'")
            if key in mapped_operations:
                errors.append(f"{where}: '{key}' is already mapped to a UI control")
            backend_operations.add(key)
        if not isinstance(reason, str) or not reason:
            errors.append(f"{where}: reason is required")
    for index, item in enumerate(global_checks):
        where = f"global_checks[{index}]"
        if not isinstance(item, dict):
            errors.append(f"{where}: must be a mapping")
            continue
        item_id = item.get("id")
        expected = item.get("expected")
        if not isinstance(item_id, str) or not item_id:
            errors.append(f"{where}: id is required")
        if not isinstance(expected, str) or not expected:
            errors.append(f"{where}: expected is required")
        check_states(item.get("states"), where)
        check_operations(item.get("operations"), where)

    unmapped = set(operations) - mapped_operations - backend_operations
    if unmapped:
        errors.append("OpenAPI operations missing from both controls and backend_only:")
        for key in sorted(unmapped):
            errors.append(f"  - {key}")
    return screens, deferred, backend_only, behaviors, errors, mapped_operations, mapping_by_operation


def render_item(
    item: dict[str, Any],
    full_id: str,
    behaviors: dict[str, str],
    controls: bool,
) -> list[str]:
    label = item.get("label") if controls else item.get("id", "").replace("-", " ").capitalize()
    item_type = f" (`{item.get('type')}`)" if controls else ""
    expected = (item.get("expected") or "").strip()
    line = f"- [ ] `{full_id}` **{label}**{item_type}"
    if expected:
        line += f" — {expected}"
    lines = [line]
    states = item.get("states") or []
    if states:
        details = "; ".join(f"`{state}`: {behaviors[state]}" for state in states)
        lines.append(f"  - State checks: {details}")
    operations = item.get("_operations") or []
    if operations:
        lines.append("  - Operations: " + ", ".join(f"`{key}`" for key in operations))
    return lines


def render(
    map_document: dict[str, Any],
    screens: list[dict[str, Any]],
    deferred: list[dict[str, Any]],
    backend_only: list[dict[str, Any]],
    behaviors: dict[str, str],
    operations: dict[str, dict[str, Any]],
    mapped_operations: set[str],
    mapping_by_operation: dict[str, list[str]],
) -> str:
    platforms = map_document["platforms"]
    global_checks = map_document.get("global_checks", [])
    backend_reasons = {item["operation"]: item["reason"] for item in backend_only}
    control_count = sum(len(screen.get("controls", []) or []) for screen in screens)
    screen_check_count = sum(len(screen.get("checks", []) or []) for screen in screens)

    lines: list[str] = [
        "# Exhaustive acceptance checklist (Test 35)",
        "",
        "<!-- Generated by `tools/acceptance/generate_acceptance_checklist.py`. Do not edit by hand. -->",
        "",
        "This checklist is generated from `docs/openapi.yaml` and `tools/acceptance/screen-map.yaml`.",
        "Every OpenAPI operation is represented here, either through at least one UI control or as a reviewed backend-only surface.",
        "",
        "Run the drift check with:",
        "",
        "```bash",
        "python3 tools/acceptance/generate_acceptance_checklist.py --check",
        "```",
        "",
        "## Coverage summary",
        "",
        "| Measure | Count |",
        "| --- | ---: |",
        f"| OpenAPI operations | {len(operations)} |",
        f"| UI-mapped operations | {len(mapped_operations)} |",
        f"| Reviewed backend-only operations | {len(backend_only)} |",
        f"| Screens | {len(screens)} |",
        f"| Screen checks | {screen_check_count} |",
        f"| Controls | {control_count} |",
        f"| Deferred surfaces | {len(deferred)} |",
        f"| Cross-cutting checks | {len(global_checks)} |",
        "",
        "## Execution prerequisites",
        "",
        "- Use a freshly migrated database with the Sprint 12 seed data; never run this checklist against production.",
        "- Prepare one active user for each role: "
        + ", ".join(f"`{role}`" for role in sorted(ROLES))
        + ".",
        "- Prepare users with one-site access and with multi-site access to verify site scoping.",
        "- Use a desktop browser for the web checks and a physical Android device for camera, offline, and WorkManager checks.",
        "- Record the build SHA, database reset time, tester, date, and evidence links in the acceptance report.",
        "",
        "## Cross-cutting checks",
        "",
    ]
    for check in global_checks:
        lines.extend(render_item(check, f"T35-GLOBAL-{slug(check['id'])}", behaviors, controls=False))

    for platform, meta in platforms.items():
        platform_screens = [screen for screen in screens if screen.get("platform") == platform]
        if not platform_screens:
            continue
        lines.extend(["", f"## {meta['title']} screens", ""])
        for screen in platform_screens:
            lines.extend(
                [
                    f"### {screen['title']}",
                    "",
                    f"- Screen ID: `{stable_id(platform, screen['id'], 'SCREEN')}`",
                    f"- Source: `{screen['source']}`",
                    f"- Roles: {', '.join(f'`{role}`' for role in screen['roles'])}",
                    f"- Entry: {screen['entry']}",
                ],
            )
            purpose = (screen.get("purpose") or "").strip()
            if purpose:
                lines.append(f"- Purpose: {purpose}")
            lines.append("")
            for check in screen.get("checks", []) or []:
                lines.extend(
                    render_item(
                        check,
                        stable_id(platform, screen["id"], check["id"]),
                        behaviors,
                        controls=False,
                    ),
                )
            for control in screen.get("controls", []) or []:
                lines.extend(
                    render_item(
                        control,
                        stable_id(platform, screen["id"], control["id"]),
                        behaviors,
                        controls=True,
                    ),
                )
            if not (screen.get("checks") or screen.get("controls")):
                lines.append("- [ ] No interactive controls on this screen; verify read-only and empty states.")
            lines.append("")

    lines.extend(["## Backend-only API surfaces", ""])
    lines.append("These operations have no current web or Android control. They still require API contract verification.")
    lines.append("")
    for item in sorted(backend_only, key=lambda entry: entry["operation"]):
        key = item["operation"]
        op = operations[key]
        lines.append(f"- [ ] `{op['id']}-NOUI` **{key}** — {op['summary'] or 'No summary.'}")
        lines.append(f"  - Reason for no UI mapping: {item['reason']}")
        lines.append(f"  - Access: {access_text(op)}")
        lines.append(f"  - Inputs: {parameter_text(op)}")
        lines.append(f"  - Expected: {response_text(op)}")
    lines.append("")

    lines.extend(["## OpenAPI operation contract checklist", ""])
    lines.append("Every operation below must be exercised with an authorised user, an unauthorised role, and a site-scoped user where applicable.")
    lines.append("")
    by_tag: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for op in operations.values():
        tags = op.get("tags") or ["Untagged"]
        by_tag[str(tags[0])].append(op)
    for tag in sorted(by_tag):
        lines.extend([f"### {tag}", ""])
        for op in sorted(by_tag[tag], key=lambda item: (item["path"], item["method"])):
            mapped = mapping_by_operation.get(op["key"], [])
            lines.append(f"- [ ] `{op['id']}` **{op['key']}** — {op['summary'] or 'No summary.'}")
            lines.append(f"  - Access: {access_text(op)}")
            lines.append(f"  - Inputs: {parameter_text(op)}")
            lines.append(f"  - Expected: {response_text(op)}")
            if op["key"] in backend_reasons:
                lines.append(f"  - UI mapping: none; {backend_reasons[op['key']]}")
            else:
                lines.append("  - UI mapping: " + ", ".join(f"`{item}`" for item in mapped))
        lines.append("")

    lines.extend(["## Deferred surfaces", ""])
    lines.append("These screens are visible in navigation but intentionally remain placeholders; verify the placeholder text and that no empty action is offered.")
    lines.append("")
    for item in deferred:
        roles = ", ".join(f"`{role}`" for role in item["roles"])
        lines.append(f"- [ ] `T35-DEFERRED-{slug(item['id'])}` **{item['id'].replace('-', ' ').title()}** — {item['reason']}")
        lines.append(f"  - Source: `{item['source']}`")
        lines.append(f"  - Roles: {roles}")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--openapi", default="docs/openapi.yaml")
    parser.add_argument("--screen-map", default="tools/acceptance/screen-map.yaml")
    parser.add_argument("--output", default="docs/acceptance-checklist.md")
    parser.add_argument("--check", action="store_true", help="fail if the generated checklist is stale")
    args = parser.parse_args()

    openapi_path = REPO_ROOT / args.openapi
    screen_map_path = REPO_ROOT / args.screen_map
    output_path = REPO_ROOT / args.output
    try:
        openapi = load_yaml(openapi_path)
        screen_map = load_yaml(screen_map_path)
        operations = collect_operations(openapi)
    except (OSError, ValueError, KeyError, yaml.YAMLError) as error:
        print(f"acceptance: {error}", file=sys.stderr)
        return 1

    screens, deferred, backend_only, behaviors, errors, mapped, mappings = validate_map(screen_map, operations)
    if errors:
        print("acceptance: screen map validation failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    generated = render(screen_map, screens, deferred, backend_only, behaviors, operations, mapped, mappings)
    if not generated.endswith("\n"):
        generated += "\n"

    if args.check:
        try:
            current = output_path.read_text(encoding="utf-8")
        except OSError as error:
            print(f"acceptance: cannot read {output_path}: {error}", file=sys.stderr)
            return 1
        if current != generated:
            print(
                f"acceptance: {args.output} is stale; run python3 tools/acceptance/generate_acceptance_checklist.py",
                file=sys.stderr,
            )
            return 1
        print(
            f"acceptance: OK - {len(operations)} operations, {len(screens)} screens, "
            f"{sum(len(screen.get('controls', []) or []) for screen in screens)} controls",
        )
        return 0

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(generated, encoding="utf-8")
    print(
        f"acceptance: wrote {args.output} - {len(operations)} operations, {len(screens)} screens, "
        f"{sum(len(screen.get('controls', []) or []) for screen in screens)} controls",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
