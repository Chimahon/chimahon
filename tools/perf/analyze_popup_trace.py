"""Summarize native popup work from a captured trace, without an attached device."""
import argparse
import json
from pathlib import Path
from perfetto.trace_processor import TraceProcessor


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('trace', type=Path)
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    report = {}
    with TraceProcessor(trace=str(args.trace)) as processor:
        queries = {
            'main_threads': "SELECT t.utid,t.tid,p.pid,p.name FROM thread t JOIN process p USING(upid) WHERE p.name LIKE '%chimahon%' AND t.is_main_thread=1",
            'main_costs': """SELECT s.name,count(*) AS calls,round(sum(s.dur)/1e6,2) AS total_ms,
                round(max(s.dur)/1e6,2) AS worst_ms FROM slice s JOIN thread_track tt ON s.track_id=tt.id
                JOIN thread t USING(utid) JOIN process p USING(upid)
                WHERE p.name LIKE '%chimahon%' AND t.is_main_thread=1 AND s.dur>0
                GROUP BY s.name ORDER BY total_ms DESC LIMIT 35""",
            'longest_main_slices': """SELECT s.id,s.name,s.ts,round(s.dur/1e6,2) AS ms FROM slice s
                JOIN thread_track tt ON s.track_id=tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
                WHERE p.name LIKE '%chimahon%' AND t.is_main_thread=1 AND s.dur>0
                ORDER BY s.dur DESC LIMIT 25""",
            'app_frames': """SELECT round(dur/1e6,2) AS ms,jank_type,present_type FROM actual_frame_timeline_slice
                JOIN process USING(upid) WHERE process.name LIKE '%chimahon%' AND dur>0 ORDER BY dur DESC LIMIT 15""",
            'main_self_costs': """WITH children AS (SELECT parent_id,sum(dur) AS dur FROM slice WHERE dur>0 GROUP BY parent_id)
                SELECT s.name,count(*) AS calls,round(sum(s.dur-coalesce(c.dur,0))/1e6,2) AS self_ms,
                round(max(s.dur-coalesce(c.dur,0))/1e6,2) AS worst_self_ms
                FROM slice s LEFT JOIN children c ON c.parent_id=s.id
                JOIN thread_track tt ON tt.id=s.track_id JOIN thread t USING(utid) JOIN process p USING(upid)
                WHERE p.name='app.chimahon.dev' AND t.is_main_thread=1 AND s.dur>0
                GROUP BY s.name ORDER BY self_ms DESC LIMIT 35""",
            'worst_measure_children': """WITH roots AS (SELECT s.id,s.ts,s.dur,s.track_id FROM slice s
                JOIN thread_track tt ON tt.id=s.track_id JOIN thread t USING(utid) JOIN process p USING(upid)
                WHERE p.name='app.chimahon.dev' AND t.is_main_thread=1 AND s.name='AndroidOwner:measureAndLayout'
                ORDER BY s.dur DESC LIMIT 3),
                children AS (SELECT parent_id,sum(dur) AS dur FROM slice WHERE dur>0 GROUP BY parent_id)
                SELECT r.id AS root,s.name,count(*) AS calls,round(sum(s.dur)/1e6,2) AS inclusive_ms,
                round(sum(s.dur-coalesce(c.dur,0))/1e6,2) AS self_ms,round(max(s.dur)/1e6,2) AS worst_ms
                FROM roots r JOIN slice s ON s.track_id=r.track_id AND s.ts>=r.ts AND s.ts+s.dur<=r.ts+r.dur
                LEFT JOIN children c ON c.parent_id=s.id WHERE s.dur>0
                GROUP BY r.id,s.name ORDER BY r.id,self_ms DESC LIMIT 90""",
            'renderer_counts': """SELECT name,count(*) AS calls,round(sum(dur)/1e6,2) AS ms,round(max(dur)/1e6,2) AS worst_ms
                FROM slice WHERE dur>0 AND (name LIKE '%PreparedParagraph%' OR name LIKE '%RubyRun%' OR name LIKE '%rememberInlineParagraph%'
                OR name LIKE '%spanText%' OR name LIKE '%TableGrid%' OR name LIKE '%TextAnnotated%' OR name LIKE '%TextString%'
                OR name LIKE '%prefetch%' OR name LIKE '%Dict%' AND name NOT LIKE '%Structured%' AND name NOT LIKE '%FlowRuns%')
                GROUP BY name ORDER BY ms DESC LIMIT 45""",
            'worst_frame_cpu': """WITH roots AS (SELECT s.id,s.ts,s.dur,t.utid FROM slice s
                JOIN thread_track tt ON tt.id=s.track_id JOIN thread t USING(utid) JOIN process p USING(upid)
                WHERE p.name='app.chimahon.dev' AND t.is_main_thread=1 AND s.name GLOB 'Choreographer#doFrame [0-9]*'
                ORDER BY s.dur DESC LIMIT 5)
                SELECT r.id,round(r.dur/1e6,2) AS frame_ms,t.state,
                round(sum(min(t.ts+t.dur,r.ts+r.dur)-max(t.ts,r.ts))/1e6,2) AS state_ms
                FROM roots r JOIN thread_state t ON t.utid=r.utid AND t.ts<r.ts+r.dur AND t.ts+t.dur>r.ts
                GROUP BY r.id,t.state ORDER BY r.id,state_ms DESC""",
        }
        for name, query in queries.items():
            report[name] = [vars(row) for row in processor.query(query)]
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    if args.out:
        args.out.write_text(rendered, encoding='utf-8')
    print(rendered)


if __name__ == '__main__':
    main()
