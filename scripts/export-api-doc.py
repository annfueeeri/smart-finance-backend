#!/usr/bin/env python3
"""Export the canonical OpenAPI YAML as JSON (requires PyYAML)."""
import json
from pathlib import Path
import yaml

root = Path(__file__).resolve().parent.parent
specification = yaml.safe_load((root / 'src/main/resources/api.yaml').read_text(encoding='utf-8'))
target = root / 'docs/smart-finance-openapi.json'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps(specification, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'Generated {target}')
