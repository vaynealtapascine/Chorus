#!/usr/bin/env python3
"""Write the seed set of conformance fixtures (inputs only; `expect` is filled by blessing).

    python scripts/gen-fixtures.py            # writes fixtures/<area>/<case>.json (skips existing)
    CHORUS_FIXTURES_BLESS=1 cargo test -p chorus-core --test fixtures
    git diff fixtures/                        # review every expectation before committing

Add new cases by hand or here; never bless without reading the diff.
"""
import io
import json
import os

ROOT = os.path.join(os.path.dirname(__file__), '..', 'fixtures')

A = '0192f8c2-0000-7000-8000-0000000000a1'   # account
M1 = '0192f8c2-0000-7000-8000-0000000000b1'  # kai
M2 = '0192f8c2-0000-7000-8000-0000000000b2'  # june
M3 = '0192f8c2-0000-7000-8000-0000000000b3'  # rin
G1 = '0192f8c2-0000-7000-8000-0000000000c1'
SP = '0192f8c2-0000-7000-8000-0000000000d1'
MSG = '0192f8c2-0000-7000-8000-0000000000e1'


def op(n, kind, payload, entity=None, at=1_790_000_000_000, node=1, scope=None, seq=None, device='dev1'):
    return {
        'id': f'0192f8c2-0000-7000-8000-{n:012x}',
        'kind': kind,
        'v': 1,
        'scope': scope or f'account:{A}',
        'entity_id': entity,
        'hlc': f'{at:012x}-0000-{node:08x}',
        'device_at': at,
        'tz_offset_min': 60,
        'payload': payload,
        'seq': seq if seq is not None else n,
        'account_id': A,
        'device_id': device,
        'occurred_at': at,
        'received_at': at,
    }


def entry(m, level='front', primary=False):
    return {'subject_type': 'member', 'subject_id': m, 'level': level, 'is_primary': primary}


NAMES = json.dumps({'mentions': {'kai': {'target_type': 'member', 'target_id': M1},
                                 'stars': {'target_type': 'group', 'target_id': G1}},
                    'emoji': {'kai_wave': 'e-kai-wave'}})
SPEAKERS = json.dumps([
    {'member_id': 'sky', 'sigils': ['🌌'], 'proxy_tags': [{'prefix': 's:', 'suffix': ''}]},
    {'member_id': 'mark', 'sigils': ['🔖'], 'proxy_tags': [{'prefix': 'm:', 'suffix': ''}]},
    {'member_id': 'fire', 'sigils': ['❤️‍🔥'], 'proxy_tags': [{'prefix': '[', 'suffix': ']'}]},
])
T = 1_790_000_000_000

CASES = {
    'hlc': {
        'tick_from_empty': ('first clock on a device', 'hlc_tick', ['', 7, 1000]),
        'tick_wall_clock_went_back': ('never goes backwards', 'hlc_tick', ['0000000007d0-0003-00000007', 7, 1000]),
        'observe_remote_ahead': ('moves past a remote clock', 'hlc_observe', ['0000000003e8-0000-00000007', 7, '0000000007d0-0005-00000009', 1000]),
        'observe_broken_remote': ('a clock days in the future is capped at now+60s', 'hlc_observe', ['', 7, 'ffffffffffff-0000-00000009', T]),
        'bad_hlc': ('malformed clock is an error', 'hlc_tick', ['nope', 7, 1000]),
    },
    'text': {
        'basic_styles': ('each style once', 'parse_markup', ['a **b** *c* __d__ ~~e~~ ||f|| `g`', '']),
        'nested_and_snake_case': ('underscores inside words are text', 'parse_markup', ['**bold _it_** and some_snake_case', '']),
        'escapes_and_separator': ('backslash escapes; \\& separates', 'parse_markup', ['\\*not\\* a\\&b', '']),
        'utf16_offsets': ('offsets count UTF-16 units: emoji take 2', 'parse_markup', ['🌌 **hi** ❤️‍🔥 *yo*', '']),
        'links_mentions_emoji_urls': ('atoms', 'parse_markup', ['[docs](https://x.org/a) @kai @stars @front @nobody :kai_wave: https://a.b/c.', NAMES]),
        'unsafe_link_scheme': ('javascript: links stay text', 'parse_markup', ['[x](javascript:alert(1))', '']),
        'quotes': ('quote groups and expandable quotes', 'parse_markup', ['> one\n> **two**\nplain\n>> more', '']),
        'pre_block': ('fenced code keeps > and backticks', 'parse_markup', ['look:\n```rust\n> not a quote\nlet x = `y`;\n```\nend', '']),
        'to_markup_crossing': ('crossing entities are split so markup nests', 'to_markup',
                               [json.dumps({'text': 'abcdef', 'entities': [{'type': 'bold', 'offset': 0, 'length': 4}, {'type': 'italic', 'offset': 2, 'length': 4}]})]),
        'to_markup_adjacent_delims': ('bold ending right after italic needs a separator', 'to_markup',
                                      [json.dumps({'text': 'bold it and', 'entities': [{'type': 'bold', 'offset': 0, 'length': 7}, {'type': 'italic', 'offset': 5, 'length': 2}]})]),
    },
    'speaker': {
        'default_speaker': ('no annotation uses the speaker chip', 'compose', ['hello', SPEAKERS, '', '["def"]', '']),
        'joint_sigils': ('stacked sigils = joint message', 'compose', ['🌌🔖❤️‍🔥 we all agree', SPEAKERS, '', '["def"]', '']),
        'segments': ('newline annotations start segments; => separator', 'compose', ['🌌 go\n🔖❤️‍🔥=> no\nstill ours', SPEAKERS, '', '["def"]', '']),
        'consecutive_tags': ('prefix tags chain with spaces', 'compose', ['s: m: hi', SPEAKERS, '', '["def"]', '']),
        'suffix_pair': ('PK-style pair around the whole message', 'compose', ['[hello]', SPEAKERS, '', '["def"]', '']),
        'escaped_sigil': ('backslash keeps a sigil as text', 'compose', ['\\🌌 not me', SPEAKERS, '', '["def"]', '']),
        'segments_off': ('segments can be turned off', 'compose', ['🌌 a\n🔖 b', SPEAKERS, '{"segments": false}', '["def"]', '']),
        'sigil_needs_space': ('a sigil glued to text is text', 'compose', ['🌌wow', SPEAKERS, '', '["def"]', '']),
    },
    'feed': {
        'spec_example_1': ('SPEC §6.4', 'feed_parse', ['from:@stars kind:entry mood:tired -tag:vent since:30d']),
        'spec_example_2': ('lists with quoted names', 'feed_parse', ['from:list:"close friends" has:image']),
        'spec_example_3': ('or + parentheses', 'feed_parse', ['(from:@kai or from:@juniper) reply:false']),
        'bad_kind': ('errors carry a position', 'feed_parse', ['tag:art kind:banana']),
        'unclosed_paren': ('missing )', 'feed_parse', ['(from:@kai']),
    },
    'color': {
        'pale_yellow_on_paper': ('darkened to 4.5:1', 'adapt_color', ['#fff27a', False, 'subtle']),
        'pale_yellow_on_ink': ('already fine on dark: untouched', 'adapt_color', ['#fff27a', True, 'subtle']),
        'navy_on_ink': ('lightened on dark', 'adapt_color', ['#1a2a6c', True, 'vivid']),
        'intensity_off': ('names use ink', 'adapt_color', ['#c0694e', False, 'off']),
        'garbage_color': ('unparseable falls back to ink', 'adapt_color', ['not a colour', False, 'subtle']),
    },
    'front': {
        'switch_add_remove': ('basic timeline', 'fold_front', [json.dumps([
            op(1, 'front.switch', {'entries': [entry(M1, primary=True)]}, entity='x', at=T),
            op(2, 'front.add', {'entry': entry(M2, 'cocon')}, at=T + 60_000),
            op(3, 'front.remove', {'subject_type': 'member', 'subject_id': M1}, at=T + 120_000),
        ])]),
        'backdated_add_before_switch': ('an offline add dated earlier is re-based', 'fold_front', [json.dumps([
            op(1, 'front.switch', {'entries': [entry(M1, primary=True)]}, at=T),
            op(3, 'front.switch', {'entries': [entry(M3, primary=True)]}, at=T + 300_000),
            op(2, 'front.add', {'entry': entry(M2)}, at=T + 200_000, seq=4),
        ])]),
        'retract_undo': ('undo removes a switch from the timeline', 'fold_front', [json.dumps([
            op(1, 'front.switch', {'entries': [entry(M1, primary=True)]}, at=T),
            op(2, 'front.switch', {'entries': [entry(M2, primary=True)]}, at=T + 1000),
            op(3, 'front.retract', {'target_op_id': op(2, 'x', {})['id']}, at=T + 5000),
        ])]),
        'amend_moves_time': ('editing a past switch reorders it', 'fold_front', [json.dumps([
            op(1, 'front.switch', {'entries': [entry(M1, primary=True)]}, at=T),
            op(2, 'front.switch', {'entries': [entry(M2, primary=True)]}, at=T + 3_600_000),
            op(3, 'front.amend', {'target_op_id': op(2, 'x', {})['id'], 'occurred_at': T - 3_600_000}, at=T + 3_700_000),
        ])]),
        'levels_order_primary': ('normalization: front, cocon, present; one primary', 'fold_front', [json.dumps([
            op(1, 'front.switch', {'entries': [entry(M3, 'present'), entry(M1, primary=True), entry(M2, 'cocon'), entry(M2, primary=True)]}, at=T),
        ])]),
    },
    'project': {
        'lww_fields': ('different fields both survive; same field: later clock wins', 'project', [json.dumps([
            op(1, 'member.create', {'name': 'Kai'}, entity=M1, at=T),
            op(2, 'member.set', {'color': '#aa5500'}, entity=M1, at=T + 10, node=2),
            op(3, 'member.set', {'name': 'Kai R'}, entity=M1, at=T + 20, node=1),
            op(4, 'member.set', {'name': 'Old'}, entity=M1, at=T + 5, node=2),
        ])]),
        'set_add_remove': ('element sets: latest of add/remove wins', 'project', [json.dumps([
            op(1, 'group.add_member', {'member_id': M1}, entity=G1, at=T),
            op(2, 'group.remove_member', {'member_id': M1}, entity=G1, at=T + 10),
            op(3, 'group.add_member', {'member_id': M2}, entity=G1, at=T + 10),
        ])]),
        'delete_restore': ('restore after delete brings it back', 'project', [json.dumps([
            op(1, 'member.create', {'name': 'Kai'}, entity=M1, at=T),
            op(2, 'member.delete', {}, entity=M1, at=T + 10),
            op(3, 'member.restore', {}, entity=M1, at=T + 20),
        ])]),
        'message_edit_revisions': ('latest edit shown, revisions counted', 'project', [json.dumps([
            op(1, 'message.send', {'channel_id': 'c1', 'authors': [M1], 'text': 'hi', 'entities': []}, entity=MSG, at=T, scope=f'space:{SP}'),
            op(2, 'message.edit', {'message_id': MSG, 'text': 'hello', 'entities': []}, entity=MSG, at=T + 10, scope=f'space:{SP}'),
            op(3, 'message.edit', {'message_id': MSG, 'text': 'hey', 'entities': []}, entity=MSG, at=T + 5, node=2, scope=f'space:{SP}'),
        ])]),
        'unknown_kind_is_opaque': ('ops from newer clients are counted, not projected', 'project', [json.dumps([
            op(1, 'future.feature', {'x': 1}, entity=M1, at=T),
        ])]),
    },
    'validate': {
        'forbidden_field': ('fields outside the catalogue are rejected', 'validate_op', [json.dumps(op(1, 'member.set', {'account_id': 'evil'}, entity=M1))]),
        'create_only_field': ('is_self only on create', 'validate_op', [json.dumps(op(1, 'member.set', {'is_self': True}, entity=M1))]),
        'wrong_scope': ('messages live in space scopes', 'validate_op', [json.dumps(op(1, 'message.send', {}, entity=MSG))]),
        'opaque_future_kind': ('unknown kinds are stored, not rejected', 'validate_op', [json.dumps(op(1, 'future.feature', {}, entity=M1))]),
        'admin_from_client': ('admin ops never come from clients', 'validate_op', [json.dumps(op(1, 'admin.purge', {}, entity=M1))]),
    },
}


def main():
    n = 0
    for area, cases in CASES.items():
        d = os.path.join(ROOT, area)
        os.makedirs(d, exist_ok=True)
        for name, (desc, fn, args) in cases.items():
            p = os.path.join(d, f'{name}.json')
            if os.path.exists(p):
                continue
            case = {'description': desc, 'fn': fn, 'args': args, 'expect': None}
            io.open(p, 'w', encoding='utf-8', newline='\n').write(json.dumps(case, ensure_ascii=False, indent=2) + '\n')
            n += 1
    print(f'wrote {n} new fixture(s)')


if __name__ == '__main__':
    main()
