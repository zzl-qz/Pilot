/** UI 夹具的明确服务端回执；断线未知场景在用例中显式覆盖，不能把网络失败模拟成自动重发。 */
export function installSubmissionFixture(api) {
  let counter = 0;
  api.submissionToken = async () => ({ requestId: `fixture-${Date.now()}-${++counter}` });
  api.acknowledgeSubmission = async () => ({});
  api.submissionResult = async () => ({ state: 'rejected', response: { message: '模拟服务端明确拒绝' } });
}
