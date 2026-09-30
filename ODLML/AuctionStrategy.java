public class AuctionStrategy implements LoadBalancingStrategy {
    @Override
    public int selectDonor(int[] info, int myId) {
        int max = 0, maxid = -1;
        for (int i = 0; i < info.length; i++) {
            if (i != myId && info[i] > max) {
                max = info[i];
                maxid = i;
            }
        }
        return (max > 0) ? maxid : -1;
    }
}
