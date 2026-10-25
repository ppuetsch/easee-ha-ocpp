"""Referenz-Rechenlogik aus easee/easee.py (Ueberschussberechnung), ohne Netzwerk.

Erzeugt golden-cases.json neben diesem Skript. Die Kotlin-Tests vergleichen SurplusCalculator
gegen diese Werte. Python-round() (Banker's rounding) ist absichtlich unveraendert uebernommen.
"""
import json
import random
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

GRID_VOLTAGE = 240
MIN_CHARGE_CURRENT = 6
MAX_CHARGE_CURRENT = 32
MIN_HOUSE_POWER = 100
MIN_EV_POWER = MIN_CHARGE_CURRENT * GRID_VOLTAGE
RANDOM_SEED = 20260725
CASE_COUNT = 400
OUTPUT_FILE = Path(__file__).with_name("golden-cases.json")


def min_required_pv_power(security_buffer: float) -> float:
    return MIN_EV_POWER + security_buffer + MIN_HOUSE_POWER


def power_available_for_ev(pv: float, grid: float, battery: float, ev: float,
                           security_buffer: float, prefer_car: bool) -> tuple[bool, float, float, float]:
    min_required = min_required_pv_power(security_buffer)
    if pv < min_required:
        return False, min_required, 0, 0
    if prefer_car:
        raw_target_power = max(ev - grid + battery, 0)
    else:
        raw_target_power = max(ev - grid + min(battery, 0), 0)
    target_power = raw_target_power - security_buffer
    return True, min_required, raw_target_power, target_power


def current_for_power(power: float, phases: int) -> int:
    raw_target_current = int(round(power / (GRID_VOLTAGE * phases), 0))
    if raw_target_current >= MAX_CHARGE_CURRENT:
        return MAX_CHARGE_CURRENT
    if raw_target_current >= MIN_CHARGE_CURRENT:
        return raw_target_current
    return 0


def target_current_for(target_power: float, phases: int) -> int:
    if target_power > 0:
        return current_for_power(target_power, phases)
    return 0


def evaluate_case(inputs: dict) -> dict:
    enough, min_required, raw, target = power_available_for_ev(
        inputs["pvPower"], inputs["gridPower"], inputs["batteryPower"], inputs["evPower"],
        inputs["securityBuffer"], inputs["preferCarOverBattery"])
    return {
        **inputs,
        "expectedEnoughPvPower": enough,
        "expectedMinRequiredPvPower": min_required,
        "expectedRawTargetPower": float(raw),
        "expectedTargetPower": float(target),
        "expectedTargetCurrent": target_current_for(target, inputs["phases"]) if enough else 0,
    }


def boundary_case(rng: random.Random, phases: int, prefer_car: bool) -> dict:
    """Ziel = (k + 0.5) * 240 * phases, also genau auf einer Rundungsgrenze (Banker's rounding)."""
    half_steps = rng.randint(2, 75) * 2 + 1
    target_power = 120 * phases * half_steps
    security_buffer = rng.choice([0, 50, 100, 200])
    grid = rng.randint(-2000, 2000)
    battery = rng.randint(-1500, 1500)
    battery_contribution = battery if prefer_car else min(battery, 0)
    ev = target_power + security_buffer + grid - battery_contribution
    return {
        "pvPower": 20000,
        "gridPower": grid,
        "batteryPower": battery,
        "evPower": ev,
        "securityBuffer": security_buffer,
        "preferCarOverBattery": prefer_car,
        "phases": phases,
    }


def random_case(rng: random.Random) -> dict:
    return {
        "pvPower": round(rng.uniform(0, 12000), 1),
        "gridPower": round(rng.uniform(-3000, 4000), 1),
        "batteryPower": round(rng.uniform(-2000, 2000), 1),
        "evPower": round(rng.uniform(0, 11000), 1),
        "securityBuffer": rng.choice([0.0, 50.0, 100.0, 150.0, 300.0]),
        "preferCarOverBattery": rng.random() < 0.5,
        "phases": rng.choice([1, 3]),
    }


def pv_threshold_case(rng: random.Random) -> dict:
    security_buffer = rng.choice([0.0, 100.0, 250.0])
    threshold = MIN_EV_POWER + security_buffer + MIN_HOUSE_POWER
    case = random_case(rng)
    case["securityBuffer"] = security_buffer
    case["pvPower"] = threshold + rng.choice([-1.0, 0.0, 1.0])
    return case


def build_cases(rng: random.Random) -> list[dict]:
    cases = []
    for index in range(CASE_COUNT):
        if index % 4 == 0:
            cases.append(boundary_case(rng, phases=rng.choice([1, 3]), prefer_car=index % 8 == 0))
        elif index % 4 == 1:
            cases.append(pv_threshold_case(rng))
        else:
            cases.append(random_case(rng))
    return [evaluate_case(case) for case in cases]


def main() -> None:
    cases = build_cases(random.Random(RANDOM_SEED))
    OUTPUT_FILE.write_text(json.dumps(cases, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    print(f"{len(cases)} Faelle nach {OUTPUT_FILE.name} geschrieben")


if __name__ == "__main__":
    main()
