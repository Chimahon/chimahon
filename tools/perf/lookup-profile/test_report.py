import unittest
import json
from report import exclusive_stages, make_report, preparation_activity, state_cost, subtract, union


class ReportTests(unittest.TestCase):
    def test_background_binder_is_owned_once_by_the_innermost_synchronous_operation(self):
        operations = [dict(id=8, pid=4, tid=7, phase='work.storage.outer', start_ns=0, end_ns=100,
                           detail='operation=1 correlation=latest-request-not-owner'),
                      dict(id=9, pid=4, tid=7, phase='work.storage.inner', start_ns=20, end_ns=80,
                           detail='operation=2 correlation=latest-request-not-owner')]
        binder = [dict(pid=4, tid=7, name='binder transaction', ts=30, dur=10, binder_destination_pid=44),
                  dict(pid=4, tid=8, name='binder transaction', ts=30, dur=10, binder_destination_pid=44)]
        activity = preparation_activity(4, 10, 90, operations, [], binder, set())
        self.assertEqual([r['binder_calls'] for r in activity['operations']], [0, 1])
        self.assertEqual(activity['operations'][1]['binder_destinations'], {'44': 1})
        self.assertEqual(activity['operations'][1]['correlated_request_id'], 9)
        self.assertEqual(activity['unattributed_binder_calls'], [dict(tid=8, calls=1)])

    def test_suspending_job_does_not_claim_binder_on_its_completion_thread(self):
        operation = dict(id=1, pid=4, tid=7, phase='work.job', start_ns=0, end_ns=100,
                         detail='wall_only;suspending')
        binder = dict(pid=4, tid=7, name='binder transaction', ts=30, dur=10)
        activity = preparation_activity(4, 0, 100, [operation], [], [binder], set())
        self.assertEqual(activity['operations'][0]['binder_calls'], 0)
        self.assertEqual(activity['unattributed_binder_calls'], [dict(tid=7, calls=1)])

    def test_competing_thread_cpu_is_clipped_to_the_preparation_window(self):
        states = [dict(pid=4, tid=7, ts=0, end=200, state='Running', io_wait=None, thread_name='worker')]
        activity = preparation_activity(4, 20, 80, [], states, [], {7})
        self.assertEqual(activity['threads'][0]['running_ms'], 60/1e6)
        self.assertEqual(activity['threads'][0]['role'], 'layout-worker')

    def test_union(self):
        self.assertEqual(union([(2, 6), (1, 3), (9, 9), (8, 10)]), [(1, 6), (8, 10)])

    def test_container_draw_does_not_count_as_glossary(self):
        events = [self.event('request.start', 0), self.event('content.container.draw', 50)]
        frame = dict(ts=40, dur=30, pid=4, present_type='On-time Present')
        request = make_report(events, frames=[frame])['requests'][0]
        self.assertNotIn('input_to_first_content_ms', request)
        self.assertNotIn('request_to_first_glossary_draw_ms', request)

    def test_draw_latency_available_without_perfetto(self):
        events = [self.event('request.start', 0), self.event('plan.ready', 50_000_000), self.event('document.first_draw', 80_000_000)]
        request = make_report(events)['requests'][0]
        self.assertEqual(request['request_to_first_glossary_draw_ms'], 80)
        window = request['main_thread_windows'][0]
        self.assertEqual(window['wall_ms'], 30)
        self.assertEqual(window['scheduling']['unknown_ns'], 30_000_000)

    def test_preparation_can_precede_publication(self):
        events = [self.event('request.start', 0), self.event('results.publish', 30),
                  self.event('plan.queued', 20), self.event('plan.ready', 50), self.event('document.first_draw', 80)]
        request = make_report(events)['requests'][0]
        self.assertTrue(all(x['ms'] >= 0 for x in request['draw_latency_ledger']))
        self.assertAlmostEqual(sum(x['ms'] for x in request['draw_latency_ledger']), 80/1e6)

    def test_document_cpu_is_not_document_wall(self):
        doc = dict(self.event('plan.document', 40), detail=json.dumps(dict(index=0, dictionary='dict',
            start_ns=10, end_ns=30, cpu_ns=12, paragraphs=2, rubyPairs=1)))
        request = make_report([self.event('request.start', 0), doc])['requests'][0]
        total = request['document_totals']['dict']
        self.assertEqual(total['cpu_ms'], 12/1e6)
        self.assertEqual(total['wall_ms'], 20/1e6)

    def test_failed_cancelled_and_coalesced_are_not_fast_completions(self):
        for phase, status in (('plan.failed', 'failed'), ('plan.cancelled', 'cancelled'), ('request.coalesced', 'coalesced')):
            with self.subTest(phase=phase):
                request = make_report([self.event('request.start', 0), self.event(phase, 10)])['requests'][0]
                self.assertEqual(request['status'], status)
                self.assertNotIn('request_to_first_glossary_draw_ms', request)

    def test_function_totals_sum_but_maximum_does_not(self):
        functions = [dict(id=1, pid=4, tid=tid, function='layout', calls=2,
                          wall_ns=wall, self_cpu_ns=cpu, max_wall_ns=peak)
                     for tid, wall, cpu, peak in [(7, 100, 70, 80), (8, 60, 40, 50)]]
        native = dict(id=1, pid=4, tid=7, wall_ns=20)
        request = make_report([self.event('request.start', 0), *functions, native])['requests'][0]
        self.assertEqual(request['functions']['layout']['calls'], 4)
        self.assertEqual(request['functions']['layout']['wall_ns'], 160)
        self.assertEqual(request['functions']['layout']['self_cpu_ns'], 110)
        self.assertEqual(request['functions']['layout']['max_wall_ns'], 80)
        self.assertEqual(request['native_inclusive'], [native])

    def test_subtract_clips_children(self):
        self.assertEqual(subtract(10, 30, [(0, 15), (20, 25), (22, 26), (29, 40)]), [(15, 20), (26, 29)])

    def test_io_wait_is_not_every_d_state(self):
        states = [dict(ts=0, end=10, state='D', io_wait=1),
                  dict(ts=10, end=20, state='D', io_wait=None),
                  dict(ts=20, end=30, state='R+', io_wait=None)]
        cost = state_cost([(5, 35)], states)
        self.assertEqual(cost, dict(io_wait_ns=5, uninterruptible_other_ns=10, runnable_ns=10, unknown_ns=5))

    def test_nested_and_parallel_work(self):
        slices = [dict(id=1, ts=0, dur=100, name='DP/1/outer', utid=1, tid=1, pid=4),
                  dict(id=2, ts=20, dur=40, name='DP/1/inner', utid=1, tid=1, pid=4),
                  dict(id=3, ts=20, dur=40, name='DP/1/parallel', utid=2, tid=2, pid=4)]
        stages = exclusive_stages(slices, [])
        self.assertEqual(stages[(4, 1, 'outer')]['self_ns'], 60)
        self.assertEqual(stages[(4, 1, 'inner')]['self_ns'], 40)
        self.assertEqual(stages[(4, 1, 'parallel')]['self_ns'], 40)

    def event(self, phase, ts, pid=4):
        return dict(id=1, pid=pid, tid=1, phase=phase, start_ns=ts, end_ns=ts, cpu_ns=0, detail='term')

    def test_frame_must_contain_draw_and_belong_to_app(self):
        events = [self.event('request.start', 0), self.event('document.first_draw', 50)]
        frames = [dict(ts=40, dur=15, pid=9, present_type='On-time Present'),
                  dict(ts=40, dur=30, pid=4, present_type='On-time Present')]
        request = make_report(events, frames=frames)['requests'][0]
        self.assertEqual(request['first_content_presentation_ns'], 70)

    def test_dropped_frame_is_not_presentation(self):
        events = [self.event('request.start', 0), self.event('document.first_draw', 50)]
        frame = dict(ts=40, dur=30, pid=4, present_type='Dropped Frame')
        request = make_report(events, frames=[frame])['requests'][0]
        self.assertNotIn('first_content_presentation_ns', request)

    def test_missing_input_never_claims_latency(self):
        request = make_report([self.event('repo.ready', 30)])['requests'][0]
        self.assertNotIn('input_to_first_content_ms', request)
        self.assertTrue(any('Missing input' in warning for warning in request['warnings']))

    def test_pid_separates_restarted_processes(self):
        report = make_report([self.event('request.start', 0, 4), self.event('request.start', 30, 9)])
        self.assertEqual(len(report['requests']), 2)

    def test_renderer_worker_and_document_metadata(self):
        config = dict(self.event('plan.config', 10), detail='schema=renderer-preparation-v2 density=2.0 fontSize=14')
        worker = dict(self.event('plan.worker', 30), tid=7, detail='documents=2 tid=999 nodeReads=40 nodeClassifications=20')
        doc = dict(self.event('plan.document', 20), tid=7,
                   detail=json.dumps(dict(index=0, dictionary='dictionary with spaces', start_ns=12, end_ns=19)))
        request = make_report([self.event('request.start', 0), config, worker, doc])['requests'][0]
        self.assertEqual(request['renderer_config']['schema'], 'renderer-preparation-v2')
        self.assertEqual(request['renderer_config']['density'], 2.0)
        self.assertEqual(request['worker_summaries'][0]['nodeClassifications'], 20)
        self.assertEqual(request['worker_summaries'][0]['tid'], 7)
        self.assertEqual(request['documents'][0]['dictionary'], 'dictionary with spaces')
        self.assertEqual(request['documents'][0]['tid'], 7)

    def test_malformed_document_does_not_abort_report(self):
        document = dict(self.event('plan.document', 20), detail='truncated JSON')
        request = make_report([self.event('request.start', 0), document])['requests'][0]
        self.assertEqual(request['documents'], [])
        self.assertTrue(any('Malformed document' in warning for warning in request['warnings']))

    def test_legacy_renderer_is_not_mistaken_for_v2(self):
        request = make_report([self.event('request.start', 0), self.event('plan.ready', 20)])['requests'][0]
        self.assertTrue(any('schema marker missing' in warning for warning in request['warnings']))


if __name__ == '__main__':
    unittest.main()
