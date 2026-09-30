public class WorkStealingStrategy implements LoadBalancingStrategy {
    private static final double THRESHOLD = 1.2; // 20% above the average

    @Override
    public int selectDonor(int[] info, int myId) {
        int total = 0;
        for (int v : info) total += Math.max(v, 0);
        double avg = total / (double) info.length;

        int best = -1;
        double bestDiff = 0.0;
        for (int i = 0; i < info.length; i++) {
            if (i == myId) continue;
            double ratio = info[i] / avg;
            if (ratio > THRESHOLD && ratio > bestDiff) {
                bestDiff = ratio;
                best = i;
            }
        }
        return best;
    }
}
