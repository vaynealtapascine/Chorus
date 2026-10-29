use chorus_core::member_order;
use serde_json::Value;

#[test]
fn client_fallbacks_share_the_portable_core_reference() {
    let fixture: Value = serde_json::from_str(include_str!("../../../fixtures/ui_member_order.json")).unwrap();
    let mut keys = Vec::new();
    for row in fixture["members"].as_array().unwrap() {
        let id = row["id"].as_str().unwrap();
        let name: Vec<u16> = if let Some(units) = row["name_utf16"].as_array() {
            units.iter().map(|u| u.as_u64().unwrap() as u16).collect()
        } else {
            row["display_name"].as_str().unwrap_or(row["name"].as_str().unwrap()).encode_utf16().collect()
        };
        let stored: Option<Vec<u16>> = row["sort_key"].as_str().map(|s| s.encode_utf16().collect());
        let key = member_order::key(&id.encode_utf16().collect::<Vec<_>>(), &name, stored.as_deref());
        assert_eq!(key, fixture["keys"][id].as_str().unwrap(), "{id}");
        keys.push((key, id));
    }
    keys.sort();
    assert_eq!(
        keys.iter().map(|(_, id)| *id).collect::<Vec<_>>(),
        fixture["order"].as_array().unwrap().iter().map(|id| id.as_str().unwrap()).collect::<Vec<_>>()
    );
}
