public interface LoadBalancingStrategy {
    int selectDonor(int[] info, int myId);
}
