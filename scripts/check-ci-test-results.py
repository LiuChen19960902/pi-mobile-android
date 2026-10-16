"""验证 CI 的两组 JVM 测试及网关一致性测试实际运行，拒绝缺失或跳过。"""
from __future__ import annotations

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

CONFORMANCE = "com.finecat.pimobile.android.GatewayConformanceTest"

def validate(root: Path) -> list[str]:
    errors: list[str] = []
    conformance_ran = False
    for module in ("shared", "androidApp"):
        reports = sorted((root / module / "build/test-results").glob("**/TEST-*.xml"))
        if not reports:
            errors.append(f"{module}: 没有测试结果")
            continue
        total = 0
        for path in reports:
            try:
                suite = ET.parse(path).getroot()
                tests = int(suite.attrib["tests"])
                failed = sum(int(suite.attrib.get(k, "0")) for k in ("failures", "errors", "skipped"))
                if tests < 0 or failed:
                    errors.append(f"{path.name}: 有失败、错误、跳过或非法统计")
                total += tests
                if module == "androidApp" and suite.attrib.get("name") == CONFORMANCE and tests > 0 and failed == 0:
                    conformance_ran = True
            except (OSError, ET.ParseError, KeyError, ValueError):
                errors.append(f"{path.name}: 无效的测试报告")
        if total <= 0:
            errors.append(f"{module}: 没有实际执行的测试")
    if not conformance_ran:
        errors.append("GatewayConformanceTest: 缺失、跳过或未通过")
    return errors

def main() -> int:
    errors = validate(Path.cwd())
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("共享层、Android 和网关一致性测试均已实际执行，零失败/错误/跳过。")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
