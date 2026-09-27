from pathlib import Path
import sys

path = Path(sys.argv[1])
s = path.read_text(encoding='utf-8')
replacements = {
    'DropdownButtonFormField<String>(value: originCity': 'DropdownButtonFormField<String>(initialValue: originCity',
    'DropdownButtonFormField<String>(value: domesticDestination': 'DropdownButtonFormField<String>(initialValue: domesticDestination',
    'DropdownButtonFormField<String>(value: countryCode': 'DropdownButtonFormField<String>(initialValue: countryCode',
    'DropdownButtonFormField<ShipmentType>(value: shipmentType': 'DropdownButtonFormField<ShipmentType>(initialValue: shipmentType',
    'DropdownButtonFormField<PreferenceProfile>(value: preference': 'DropdownButtonFormField<PreferenceProfile>(initialValue: preference',
    'DropdownButtonFormField<SortMode>(value: sort': 'DropdownButtonFormField<SortMode>(initialValue: sort',
    'DropdownButtonFormField<double?>(value: localPrice': 'DropdownButtonFormField<double?>(initialValue: localPrice',
    'DropdownButtonFormField<int?>(value: localEta': 'DropdownButtonFormField<int?>(initialValue: localEta',
    'DropdownButtonFormField<ServiceKind?>(value: localKind': 'DropdownButtonFormField<ServiceKind?>(initialValue: localKind',
    '    ))));\n  }\n}\n\nclass QuoteCard': '    )));\n  }\n}\n\nclass QuoteCard',
    '  ]))))));\n}\n\nFuture<void> openUrl': '  ])))));\n}\n\nFuture<void> openUrl',
}
for old, new in replacements.items():
    if old not in s:
        raise SystemExit(f'Expected source fragment not found: {old[:80]}')
    s = s.replace(old, new, 1)
path.write_text(s, encoding='utf-8')
print('Applied analyzer fixes to', path)
