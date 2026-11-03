"""Removable lookup profiler. Standard library only unless --trace is supplied."""
import argparse
from bisect import bisect_right
from collections import defaultdict
import csv
import json
from pathlib import Path
import re


def union(intervals):
    result = []
    for start, end in sorted(intervals):
        if end <= start:
            continue
        if result and start <= result[-1][1]:
            result[-1] = (result[-1][0], max(end, result[-1][1]))
        else:
            result.append((start, end))
    return result


def subtract(start, end, intervals):
    result = []
    cursor = start
    for left, right in union(intervals):
        left, right = max(start, left), min(end, right)
        if right <= left:
            continue
        if left > cursor:
            result.append((cursor, left))
        cursor = max(cursor, right)
    if cursor < end:
        result.append((cursor, end))
    return result


def read_events(path):
    events, malformed = [], 0
    for line in Path(path).read_text(encoding='utf-8-sig', errors='replace').splitlines():
        if not re.search(r'\bDictProfile(?:Native)?\s*:', line):
            continue
        try:
            value = json.loads(line[line.index('{'):])
            if value.get('id', 0) > 0:
                events.append(value)
        except (ValueError, TypeError):
            malformed += 1
    return events, malformed


def state_cost(windows, states):
    """states are sorted, disjoint thread_state intervals; gaps remain unknown."""
    cost = defaultdict(int)
    starts = [row['ts'] for row in states]
    for start, end in windows:
        covered = 0
        i = max(0, bisect_right(starts, start) - 1)
        while i < len(states) and states[i]['ts'] < end:
            row = states[i]
            overlap = max(0, min(end, row['end']) - max(start, row['ts']))
            if overlap:
                state = row['state']
                if state == 'Running':
                    kind = 'running_ns'
                elif state.startswith('R'):
                    kind = 'runnable_ns'
                elif state.startswith('D') and row['io_wait'] == 1:
                    kind = 'io_wait_ns'
                elif state.startswith('D'):
                    kind = 'uninterruptible_other_ns'
                else:
                    kind = 'other_wait_ns'
                cost[kind] += overlap
                covered += overlap
            i += 1
        cost['unknown_ns'] += max(0, end - start - covered)
    return dict(cost)


def trace_data(path, events=()):
    from perfetto.trace_processor import TraceProcessor
    with TraceProcessor(trace=str(path)) as tp:
        def rows(sql):
            return [vars(row) for row in tp.query(sql)]
        slices = rows('''
            SELECT s.id, s.ts, s.dur, s.name, tt.utid, t.tid, p.pid
            FROM slice s JOIN thread_track tt ON tt.id=s.track_id
            JOIN thread t USING(utid) LEFT JOIN process p USING(upid)
            WHERE s.name GLOB 'DP/*' AND s.dur >= 0 ORDER BY s.ts, s.dur DESC
        ''')
        stats = rows("SELECT name,value,severity FROM stats WHERE value>0 AND severity!='info'")
        frames = rows('''SELECT f.ts,f.dur,f.upid,p.pid,f.jank_type,f.present_type
            FROM actual_frame_timeline_slice f JOIN process p USING(upid)
            WHERE f.dur>=0 ORDER BY f.ts''')
        pids = sorted({int(e['pid']) for e in [*events, *slices] if e.get('pid') is not None})
        windows = [(e['start_ns'], e['end_ns']) for e in events if 'start_ns' in e and 'end_ns' in e]
        windows += [(s['ts'], s['ts'] + s['dur']) for s in slices]
        pid_filter = ','.join(map(str, pids)) or '-1'
        if windows:
            left = min(a for a, b in windows)
            right = max(b for a, b in windows)
            states = rows(f'''SELECT s.utid,s.ts,s.dur,s.state,s.io_wait,s.blocked_function,t.tid,p.pid,t.name thread_name
                FROM thread_state s JOIN thread t USING(utid) JOIN process p USING(upid)
                WHERE p.pid IN ({pid_filter}) AND s.ts<{right} AND (s.dur=-1 OR s.ts+s.dur>{left})
                ORDER BY s.utid,s.ts''')
            for state in states:
                state['end'] = right if state['dur'] < 0 else state['ts'] + state['dur']
        else:
            states = []
        generic = rows(f'''SELECT s.ts,s.dur,s.name,t.tid,p.pid,t.is_main_thread,
            EXTRACT_ARG(s.arg_set_id,'destination process') binder_destination_pid
            FROM slice s JOIN thread_track tt ON tt.id=s.track_id
            JOIN thread t USING(utid) JOIN process p USING(upid)
            WHERE (s.dur>=1000000 OR (s.name='binder transaction' AND s.dur>=0)) AND s.name NOT GLOB 'DP/*'
            AND p.pid IN ({pid_filter})''')
        return slices, states, frames, stats, generic


def preparation_activity(pid, start, end, events, states, generic, layout_tids):
    """Correlate competing work; only synchronous operation spans own Binder calls."""
    operations = [e for e in events if e.get('pid') == pid and e.get('phase', '').startswith('work.')
                  and e.get('end_ns', 0) > start and e.get('start_ns', end) < end]
    rows = [dict(name=e['phase'], tid=e['tid'], correlated_request_id=e['id'], detail=e.get('detail', ''),
                 overlap_ms=(min(end, e['end_ns'])-max(start, e['start_ns']))/1e6,
                 full_wall_ms=(e['end_ns']-e['start_ns'])/1e6, binder_calls=0,
                 full_cpu_ms=None if 'wall_only' in e.get('detail', '') else e.get('cpu_ns', 0)/1e6,
                 enclosing_operations=[], binder_destinations={}) for e in operations]
    unattributed = defaultdict(int)
    for item in generic:
        if item.get('pid') != pid or item['name'] != 'binder transaction' or item['ts'] >= end or item['ts']+item['dur'] <= start:
            continue
        containing = [i for i, e in enumerate(operations) if e['tid'] == item['tid']
                      and 'operation=' in e.get('detail', '')
                      and e['start_ns'] <= item['ts'] and item['ts']+item['dur'] <= e['end_ns']]
        if not containing:
            unattributed[item['tid']] += 1
            continue
        # Nested discovery scopes are not separate transactions. Attribute each call once.
        owner = min(containing, key=lambda i: operations[i]['end_ns']-operations[i]['start_ns'])
        rows[owner]['binder_calls'] += 1
        parents = sorted((i for i in containing if i != owner),
                         key=lambda i: operations[i]['end_ns']-operations[i]['start_ns'], reverse=True)
        rows[owner]['enclosing_operations'] = [operations[i]['phase'] for i in parents]
        destination = str(item.get('binder_destination_pid') or 'unknown')
        destinations = rows[owner]['binder_destinations']
        destinations[destination] = destinations.get(destination, 0) + 1
    threads = defaultdict(list)
    for state in states:
        if state.get('pid') == pid:
            threads[state['tid']].append(state)
    running = []
    for tid, thread_states in threads.items():
        cost = state_cost([(start, end)], sorted(thread_states, key=lambda s: s['ts']))
        if cost.get('running_ns', 0) <= 0:
            continue
        running.append(dict(tid=tid, name=thread_states[0].get('thread_name', ''),
            role='main' if tid == pid else 'layout-worker' if tid in layout_tids else 'other-app-work',
            running_ms=cost['running_ns']/1e6))
    return dict(window_ms=(end-start)/1e6, operations=rows,
                threads=sorted(running, key=lambda r: -r['running_ms']),
                unattributed_binder_calls=[dict(tid=t, calls=n) for t, n in sorted(unattributed.items())])


def exclusive_stages(slices, states):
    by_thread = defaultdict(list)
    for state in states:
        by_thread[state['utid']].append(state)
    stack = defaultdict(list)
    children = defaultdict(list)
    parsed = []
    for item in sorted(slices, key=lambda s: (s['ts'], -s['dur'])):
        match = re.fullmatch(r'DP/(\d+)/(.*)', item['name'])
        if not match:
            continue
        item = dict(item, request=int(match[1]), phase=match[2], end=item['ts']+item['dur'])
        parents = stack[item['utid']]
        while parents and item['ts'] >= parents[-1]['end']:
            parents.pop()
        if parents and item['end'] <= parents[-1]['end']:
            children[parents[-1]['id']].append((item['ts'], item['end']))
        parents.append(item)
        parsed.append(item)
    stages = defaultdict(lambda: defaultdict(int))
    for item in parsed:
        key = (item['pid'], item['request'], item['phase'])
        target = stages[key]
        windows = subtract(item['ts'], item['end'], children[item['id']])
        target['calls'] += 1
        target['inclusive_ns'] += item['dur']
        target['self_ns'] += sum(end-start for start, end in windows)
        for name, value in state_cost(windows, by_thread[item['utid']]).items():
            target[name] += value
    return stages


def make_report(events, slices=(), states=(), frames=(), stats=(), generic=(), malformed=0):
    grouped = defaultdict(list)
    for event in events:
        grouped[(event.get('pid'), event['id'])].append(event)
    stages = exclusive_stages(slices, states)
    requests = []
    for (pid, request_id), items in sorted(grouped.items(), key=lambda pair: min(e.get('start_ns', 2**63) for e in pair[1])):
        start_event = next((e for e in items if e.get('phase') == 'request.start'), None)
        markers = [e for e in items if 'start_ns' in e and e['start_ns'] == e['end_ns']]
        phases = [e for e in items if 'start_ns' in e and e['end_ns'] > e['start_ns']]
        native = [e for e in items if 'wall_ns' in e and 'function' not in e]
        # Function aggregates are a THIRD record type: they carry "function" plus calls/wall_ns/
        # cpu_ns/self_wall_ns/self_cpu_ns and no start_ns, so they are neither phase records nor
        # the native JNI records. They are the only place the renderer's own cost appears, and
        # they are aggregated across parallel workers, so the totals are a CPU sum, not latency.
        functions = defaultdict(lambda: dict(calls=0, wall_ns=0, cpu_ns=0, self_wall_ns=0,
                                             self_cpu_ns=0, max_wall_ns=0))
        for event in items:
            name = event.get('function')
            if not name:
                continue
            entry = functions[name]
            for field in entry:
                if field == 'max_wall_ns':
                    continue
                entry[field] += event.get(field, 0) or 0
            entry['max_wall_ns'] = max(entry['max_wall_ns'], event.get('max_wall_ns', 0) or 0)
        counters = defaultdict(int)
        for event in items:
            if 'counter' in event:
                counters[event['counter']] += event['value']
        request = dict(pid=pid, id=request_id, query=start_event.get('detail') if start_event else None,
                       markers=markers, measured_phases=phases, native_inclusive=native,
                       functions={name: dict(entry) for name, entry in functions.items()},
                       dictionaries={e['dictionary_phase']: e['dictionary_name'] for e in items if 'dictionary_phase' in e},
                       counters=dict(counters), stages=[], warnings=[])
        def fields(detail):
            result = {}
            for key, value in re.findall(r'(\w+)=([^\s]+)', detail):
                try:
                    result[key] = json.loads(value)
                except ValueError:
                    result[key] = value
            return result
        config = [e for e in markers if e['phase'] == 'plan.config']
        request['renderer_config'] = fields(config[-1]['detail']) if config else None
        identity = next((e for e in markers if e['phase'] == 'capture.identity'), None)
        request['capture_identity'] = fields(identity['detail']) if identity else None
        renderer = next((e for e in markers if e['phase'] == 'popup.renderer'), None)
        request['renderer'] = fields(renderer['detail']).get('renderer') if renderer else None
        request['phase_totals'] = {}
        for event in phases:
            phase = event['phase']
            total = request['phase_totals'].setdefault(phase, dict(calls=0, wall_ms=0., cpu_ms=0.))
            total['calls'] += 1
            total['wall_ms'] += (event['end_ns'] - event['start_ns']) / 1e6
            total['cpu_ms'] += event.get('cpu_ns', 0) / 1e6
        request['worker_summaries'] = [dict(fields(e['detail']), tid=e['tid'], ts=e['start_ns'])
                                       for e in markers if e['phase'] == 'plan.worker']
        request['documents'] = []
        request['source_parses'] = []
        for event in markers:
            if event['phase'] == 'plan.source':
                try:
                    source = json.loads(event['detail'])
                    if not isinstance(source, dict) or not all(isinstance(source.get(k), int) for k in ('start_ns','end_ns','cpu_ns')):
                        raise ValueError('Invalid source timing')
                    if source['end_ns'] < source['start_ns'] or source['cpu_ns'] < 0:
                        raise ValueError('Reversed source timing')
                    source['tid'] = event['tid']
                    request['source_parses'].append(source)
                except ValueError:
                    request['warnings'].append('Malformed source parse metadata.')
            if event['phase'] != 'plan.document':
                continue
            try:
                document = json.loads(event['detail'])
                if not isinstance(document, dict):
                    raise ValueError('Document metadata is not an object')
                if not all(isinstance(document.get(k), int) for k in ('start_ns', 'end_ns')):
                    raise ValueError('Document timing is missing or not numeric')
                if document['end_ns'] < document['start_ns']:
                    raise ValueError('Document timing is reversed')
                document['tid'] = event['tid']
                request['documents'].append(document)
            except ValueError:
                request['warnings'].append('Malformed document diagnostic metadata; attribution is incomplete.')
        document_totals = defaultdict(lambda: dict(documents=0, wall_ms=0., cpu_ms=0., cpu_documents=0, paragraphs=0, rubyPairs=0, drawItems=0))
        for doc in request['documents']:
            total = document_totals[doc.get('dictionary', '?')]
            total['documents'] += 1
            total['wall_ms'] += (doc['end_ns']-doc['start_ns'])/1e6
            if 'cpu_ns' in doc:
                total['cpu_ms'] += doc['cpu_ns']/1e6
                total['cpu_documents'] += 1
            for key in ('paragraphs', 'rubyPairs', 'drawItems'):
                total[key] += doc.get(key, 0)
        request['document_totals'] = dict(document_totals)
        source_totals = defaultdict(lambda: dict(sources=0, cpu_ms=0., wall_ms=0., sourceUtf16Units=0))
        for source in request['source_parses']:
            total = source_totals[source.get('dictionary', '?')]
            total['sources'] += 1
            total['cpu_ms'] += source['cpu_ns']/1e6
            total['wall_ms'] += (source['end_ns']-source['start_ns'])/1e6
            total['sourceUtf16Units'] += source.get('sourceUtf16Units', 0)
        request['source_parse_totals'] = dict(source_totals)
        def first(phase):
            return min((e['start_ns'] for e in markers if e['phase'] == phase), default=None)
        glossary_draw = first('document.first_draw')
        request['status'] = ('drawn' if glossary_draw is not None else 'failed' if first('plan.failed') is not None
            else 'cancelled' if first('plan.cancelled') is not None
            else 'coalesced' if first('request.coalesced') is not None else 'incomplete-or-empty')
        if glossary_draw is not None:
            request['first_glossary_draw_ns'] = glossary_draw
            if start_event:
                request['request_to_first_glossary_draw_ms'] = (glossary_draw-start_event['start_ns'])/1e6
        ledger = [(first(name), name) for name in ('request.start', 'repo.started', 'repo.ready',
            'host.popup.publish', 'popup.main.resume', 'results.publish', 'plan.queued',
            'plan.ready', 'plan.ui.publish', 'document.bind', 'document.first_draw')]
        # Preparation may now begin BEFORE publication; order actual timestamps, not phase names.
        ledger = sorted((ts, name) for ts, name in ledger if ts is not None and (glossary_draw is None or ts <= glossary_draw))
        request['draw_latency_ledger'] = [dict(start=a[1], end=b[1], ms=(b[0]-a[0])/1e6)
                                          for a, b in zip(ledger, ledger[1:])]
        main_states = [s for s in states if s.get('pid') == pid and s.get('tid') == pid]
        main_states.sort(key=lambda s: s['ts'])
        request['main_thread_windows'] = []
        prep_start = first('plan.config')
        prep_end = first('plan.ready')
        if prep_start is not None and prep_end is not None and prep_end > prep_start:
            layout_tids = {d['tid'] for d in request['documents'] if d.get('tid') is not None
                           and d['start_ns'] < prep_end and d['end_ns'] > prep_start}
            request['preparation_activity'] = preparation_activity(pid, prep_start, prep_end,
                events, states, generic, layout_tids)
        for left, right in (('repo.ready', 'results.publish'), ('results.publish', 'plan.queued'),
                            ('plan.ready', 'plan.ui.publish'), ('plan.ready', 'document.first_draw'),
                            ('plan.ui.publish', 'document.bind'), ('document.bind', 'document.first_draw'),
                            ('document.first_draw', 'document.frame_commit')):
            a, b = first(left), first(right)
            if a is None or b is None or b < a:
                continue
            cost = state_cost([(a, b)], main_states)
            overlapping = [dict(name=s['name'], overlap_ms=(min(s['ts']+s['dur'], b)-max(s['ts'], a))/1e6)
                           for s in generic if s['pid'] == pid and s.get('is_main_thread') and s['ts'] < b and s['ts']+s['dur'] > a]
            request['main_thread_windows'].append(dict(start=left, end=right, wall_ms=(b-a)/1e6,
                scheduling=cost, overlapping_slices=sorted(overlapping, key=lambda x:-x['overlap_ms'])[:30]))
        if any(e['phase'] == 'plan.ready' for e in markers) and not config:
            request['warnings'].append('Renderer schema marker missing; renderer diagnostics are not confirmed in this APK.')
        for (stage_pid, stage_id, phase), cost in stages.items():
            if (stage_pid, stage_id) == (pid, request_id):
                request['stages'].append(dict(phase=phase, **cost))
        request['stages'].sort(key=lambda stage: stage['self_ns'], reverse=True)
        draws = [e['start_ns'] for e in markers if e.get('phase') == 'document.first_draw']
        if not draws and first('content.container.draw') is not None:
            request['warnings'].append('Only container draw recorded; glossary completion is unproven.')
        # ActualFrameTimeline ends at presentation. Require a containing app frame, not simply
        # the next frame on any process; even this is first content, not all visible content.
        candidate = []
        if draws:
            first_draw = min(draws)
            candidate = [f for f in frames if f['pid'] == pid and f['ts'] <= first_draw < f['ts']+f['dur']
                         and f['present_type'] != 'Dropped Frame']
        if candidate and start_event:
            frame = min(candidate, key=lambda f: f['ts']+f['dur'])
            request['first_content_presentation_ns'] = frame['ts']+frame['dur']
            request['input_to_first_content_ms'] = (frame['ts']+frame['dur']-start_event['start_ns'])/1e6
            request['first_content_frame'] = frame
            start, end = start_event['start_ns'], frame['ts']+frame['dur']
            probes = [(s['ts'], s['ts']+s['dur']) for s in slices
                      if s['pid'] == pid and s['name'].startswith(f'DP/{request_id}/')]
            gaps = subtract(start, end, probes)
            request['unprobed_windows'] = [dict(start_ns=left, end_ns=right) for left, right in gaps]
            request['unprobed_wall_ms'] = sum(right-left for left, right in gaps)/1e6
            request['overlapping_system_slices'] = sorted(
                [s for s in generic if s['pid'] == pid and s['ts'] < end and s['ts']+s['dur'] > start],
                key=lambda s: s['dur'], reverse=True)[:40]
            # A non-overlapping latency ledger, unlike the sum of parallel/inclusive spans.
            checkpoints = [(start, 'input')]
            for phase in ('repo.started', 'repo.ready', 'host.popup.publish', 'results.publish',
                          'plan.queued', 'plan.prepare', 'plan.ready', 'document.first_draw'):
                times = [e['start_ns'] for e in items if e.get('phase') == phase and 'start_ns' in e]
                if times and start <= min(times) <= end:
                    checkpoints.append((min(times), phase))
            checkpoints.append((end, 'first-content presentation'))
            checkpoints.sort()
            request['latency_ledger'] = [dict(start=left[1], end=right[1], ms=(right[0]-left[0])/1e6)
                                         for left, right in zip(checkpoints, checkpoints[1:])]
        else:
            request['warnings'].append('No matched first-content presentation; no end-to-end claim.')
        if not start_event:
            request['warnings'].append('Missing input marker (partial capture or dropped log).')
        if any(e.get('phase') == 'request.coalesced' for e in markers):
            request['warnings'].append('Coalesced into source request; do not treat as a new completed lookup.')
        if not request['stages'] and not functions:
            request['warnings'].append('No function summaries or DP slices; function attribution unavailable.')
        if renderer and request['renderer'] == 'native':
            if request['phase_totals'].get('plan.prepare', {}).get('calls', 0) > 1:
                request['warnings'].append('Multiple layout plans for one request; check environment changes or rejected host preparation.')
            if request['phase_totals'].get('plan.cards_parse', {}).get('calls', 0) > 1:
                request['warnings'].append('Multiple card parsing stages for one request.')
            if not config:
                request['warnings'].append('Native popup has no plan configuration.')
            if not functions:
                request['warnings'].append('Native popup has no function summaries.')
            if glossary_draw is None:
                request['warnings'].append('No native glossary draw: cancelled, failed, empty or incomplete lookup.')
        requests.append(request)
    return dict(requests=requests, malformed_profile_lines=malformed, trace_stats=list(stats),
                limitations=[
                    'Self time excludes nested DP probes on the same thread. Parallel thread times are not additive latency.',
                    'Native and measured phase totals are inclusive. Do not sum parent and child totals.',
                    'Page faults are counts, not milliseconds. No major faults does not rule out storage waits.',
                    'I/O waits require io_wait=1. Other D-state waits are deliberately not called disk I/O.',
                    'First content is not proof that every visible glossary or image is complete.',
                    'Missing scheduling intervals remain unknown. Profiling adds overhead; compare a baseline capture.',
                ])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--logcat', required=True, type=Path)
    parser.add_argument('--trace', type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    events, malformed = read_events(args.logcat)
    if not events:
        parser.error('No DictProfile JSON events. Use an instrumented debug build and capture.ps1 without -Baseline.')
    data = trace_data(args.trace, events) if args.trace else ([], [], [], [], [])
    report = make_report(events, *data, malformed=malformed)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.with_suffix('.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    columns = ['pid', 'id', 'phase', 'calls', 'inclusive_ns', 'self_ns', 'running_ns', 'runnable_ns',
               'io_wait_ns', 'uninterruptible_other_ns', 'other_wait_ns', 'unknown_ns']
    with args.out.with_suffix('.csv').open('w', newline='', encoding='utf-8') as handle:
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        for request in report['requests']:
            for stage in request['stages']:
                writer.writerow(dict(pid=request['pid'], id=request['id'], **stage))
    lines = ['# Lookup Profile', '', *('- '+note for note in report['limitations']), '']
    native = [r for r in report['requests'] if r.get('renderer') == 'native']
    lines += ['## Native Lookup Summary', '',
              '| Request | Query | Request -> glossary draw ms | Plan ms | Ready -> draw ms | Documents |',
              '|---:|---|---:|---:|---:|---:|']
    lookup_rows, function_rows, document_rows, source_rows = [], [], [], []
    for r in report['requests']:
        ready = next((w['wall_ms'] for w in r['main_thread_windows'] if w['start']=='plan.ready' and w['end']=='document.first_draw'), None)
        plan = r['phase_totals'].get('plan.prepare', {}).get('wall_ms')
        def fmt(value): return 'unavailable' if value is None else f'{value:.2f}'
        if r.get('renderer') == 'native':
            query = str(r['query']).replace('|', '\\|').replace('\n', ' ')
            lines.append(f"| {r['id']} | {query} | {fmt(r.get('request_to_first_glossary_draw_ms'))} | {fmt(plan)} | {fmt(ready)} | {len(r['documents'])} |")
        lookup_rows.append(dict(pid=r['pid'], id=r['id'], query=r['query'], renderer=r.get('renderer'),
            status=r['status'],
            request_to_draw_ms=r.get('request_to_first_glossary_draw_ms'), prepare_ms=plan, ready_to_draw_ms=ready,
            presentation_ms=r.get('input_to_first_content_ms'), documents=len(r['documents'])))
        for name, f in r['functions'].items():
            function_rows.append(dict(pid=r['pid'], id=r['id'], function=name, **f))
        for doc in r['documents']:
            document_rows.append(dict(pid=r['pid'], id=r['id'], **{k:v for k,v in doc.items() if k!='stages'},
                **{'stage_'+k:v for k,v in doc.get('stages',{}).items()}))
        source_rows.extend(dict(pid=r['pid'], id=r['id'], **source) for source in r['source_parses'])
    lines += ['', 'Draw means glossary Canvas recording, not proof of presentation. Function CPU is summed across workers; phase and document times can overlap.', '']
    for suffix, rows in (('lookups', lookup_rows), ('functions', function_rows), ('documents', document_rows), ('sources', source_rows)):
        if rows:
            keys = list(dict.fromkeys(k for row in rows for k in row))
            with args.out.with_name(args.out.name+'-'+suffix).with_suffix('.csv').open('w', newline='', encoding='utf-8-sig') as handle:
                writer = csv.DictWriter(handle, fieldnames=keys)
                writer.writeheader()
                writer.writerows(rows)
    if report['trace_stats'] or malformed:
        lines += [f"Trace quality: {len(report['trace_stats'])} nonzero error/data-loss stats; {malformed} malformed log lines.", '']
    for request in report['requests']:
        elapsed = request.get('input_to_first_content_ms')
        lines += [f"## Request {request['pid']}/{request['id']}: {request['query']}", '',
                  'Request start to matched frame presentation: '+(f'{elapsed:.2f} ms' if elapsed is not None else 'unavailable'), '']
        lines += ['- '+warning for warning in request['warnings']]
        if request.get('capture_identity'):
            lines += ['- Build/timer identity: '+json.dumps(request['capture_identity'], ensure_ascii=False)]
        if 'request_to_first_glossary_draw_ms' in request:
            lines += [f"- Request to first glossary draw: {request['request_to_first_glossary_draw_ms']:.2f} ms"]
        for interval in request['draw_latency_ledger']:
            lines.append(f"- {interval['start']} -> {interval['end']}: {interval['ms']:.3f} ms")
        if request['main_thread_windows']:
            lines += ['', '| Main-thread interval | Wall ms | Running ms | Runnable ms | I/O wait ms | Unknown ms |', '|---|---:|---:|---:|---:|---:|']
            for window in request['main_thread_windows']:
                cost = window['scheduling']
                lines.append(f"| {window['start']} -> {window['end']} | {window['wall_ms']:.2f} | "+
                    ' | '.join(f'{cost.get(k,0)/1e6:.2f}' for k in ('running_ns','runnable_ns','io_wait_ns','unknown_ns'))+' |')
            lines += ['', 'Overlapping framework slices are in JSON; they are nested, not additive.']
        activity = request.get('preparation_activity')
        if activity:
            lines += ['', '| Preparation thread | Role | Running CPU ms |', '|---|---|---:|']
            for thread in activity['threads'][:12]:
                lines.append(f"| {thread['tid']} {thread['name']} | {thread['role']} | {thread['running_ms']:.2f} |")
            if activity['operations']:
                lines += ['', '| Concurrent operation | TID | Overlap ms | Owned Binder calls |', '|---|---:|---:|---:|']
                for operation in sorted(activity['operations'], key=lambda r: -r['overlap_ms']):
                    lines.append(f"| {operation['name']} | {operation['tid']} | {operation['overlap_ms']:.2f} | {operation['binder_calls']} |")
                lines += ['', 'Operations can nest and overlap; do not add their durations. Latest-request correlation does not establish lookup ownership. Parent operations and CPU are in JSON.']
            lines += ['Thread roles identify threads used by layout, not the owner of every job later scheduled on them.']
            unowned = sum(row['calls'] for row in activity['unattributed_binder_calls'])
            lines += [f'Unattributed Binder calls during preparation: {unowned}. Detailed destinations and thread counts are in JSON.']
        if request['document_totals']:
            lines += ['', '| Dictionary | Documents | Document CPU ms | Summed wall ms | Paragraphs | Ruby pairs |', '|---|---:|---:|---:|---:|---:|']
            for name, total in sorted(request['document_totals'].items(), key=lambda pair:-pair[1]['wall_ms']):
                cpu = f"{total['cpu_ms']:.2f}" if total['cpu_documents']==total['documents'] else 'unavailable'
                name = name.replace('|','\\|')
                lines.append(f"| {name} | {total['documents']} | {cpu} | {total['wall_ms']:.2f} | {total['paragraphs']} | {total['rubyPairs']} |")
        if request['source_parse_totals']:
            lines += ['', '| Dictionary parsing | Sources | CPU ms | Summed wall ms | UTF-16 units |', '|---|---:|---:|---:|---:|']
            for name, total in sorted(request['source_parse_totals'].items(), key=lambda pair:-pair[1]['cpu_ms']):
                name = name.replace('|','\\|')
                lines.append(f"| {name} | {total['sources']} | {total['cpu_ms']:.2f} | {total['wall_ms']:.2f} | {total['sourceUtf16Units']} |")
        for interval in request.get('latency_ledger', []):
            lines.append(f"- {interval['start']} -> {interval['end']}: {interval['ms']:.3f} ms")
        if 'unprobed_wall_ms' in request:
            lines.append(f"- Outside explicit probes: {request['unprobed_wall_ms']:.3f} ms (overlapping framework slices in JSON).")
        if request['worker_summaries']:
            lines += ['', '| Worker TID | Documents | Elements | CSS Resolutions | Paragraphs | Ruby Pairs | Font Profiles |',
                      '|---:|---:|---:|---:|---:|---:|---:|']
            for worker in request['worker_summaries']:
                # The current native-flow-v1 worker summary reports elements/css/paragraphs/rubyPairs
                # and fontProfiles. The old Compose schema used nodeReads/nodeClassifications/cssReads,
                # which is why these columns used to read "?".
                lines.append(f"| {worker['tid']} | {worker.get('documents', '?')} | "
                             f"{worker.get('elements', '?')} | {worker.get('css', '?')} | "
                             f"{worker.get('paragraphs', '?')} | {worker.get('rubyPairs', '?')} | "
                             f"{worker.get('fontProfiles', '?')} |")
        if request.get('functions'):
            lines += ['', '| Function | Calls | Self CPU ms | Wall ms | Max wall ms |', '|---|---:|---:|---:|---:|']
            for name, entry in sorted(request['functions'].items(),
                                      key=lambda kv: -kv[1]['self_cpu_ns']):
                lines.append(f"| {name} | {entry['calls']} | {entry['self_cpu_ns']/1e6:.2f} | "
                             f"{entry['wall_ns']/1e6:.2f} | {entry['max_wall_ns']/1e6:.2f} |")
            lines += ['', 'Function totals are self CPU summed across parallel workers. They are NOT latency.']
        if request['documents']:
            lines += ['', 'Largest document preparation spans (overlap across workers):', '',
                      '| Index | Dictionary | Wall ms | Paragraphs | Ruby Pairs |', '|---:|---|---:|---:|---:|']
            for doc in sorted(request['documents'], key=lambda d: d['end_ns']-d['start_ns'], reverse=True)[:10]:
                dictionary = str(doc.get('dictionary', '?')).replace('|', '\\|').replace('\n', ' ')
                lines.append(f"| {doc.get('index', '?')} | {dictionary} | {(doc['end_ns']-doc['start_ns'])/1e6:.3f} | "
                             f"{doc.get('paragraphs', '?')} | {doc.get('rubyPairs', '?')} |")
        lines += ['', '| Phase | Calls | Self ms | Running | Runnable | I/O wait | Other wait | Unknown |',
                  '|---|---:|---:|---:|---:|---:|---:|---:|']
        for stage in request['stages'][:30]:
            fields = [stage['self_ns'], stage.get('running_ns', 0), stage.get('runnable_ns', 0),
                      stage.get('io_wait_ns', 0), stage.get('other_wait_ns', 0)+stage.get('uninterruptible_other_ns', 0),
                      stage.get('unknown_ns', 0)]
            lines.append('| '+stage['phase']+' | '+str(stage['calls'])+' | '+' | '.join(f'{value/1e6:.3f}' for value in fields)+' |')
        lines += ['', 'Full inclusive timings, native fault counts, dictionary paths, counters and trace-quality details are in JSON.', '']
    args.out.with_suffix('.md').write_text('\n'.join(lines), encoding='utf-8')
    print(f"Reported {len(report['requests'])} requests to {args.out.with_suffix('.md')}")


if __name__ == '__main__':
    main()
