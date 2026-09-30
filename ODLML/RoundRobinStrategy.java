public class RoundRobinStrategy implements LoadBalancingStrategy {
    private int last = -1;

    @Override
    public int selectDonor(int[] info, int myId) {
        // Advance circularly until a candidate with data is found
        for (int k = 0; k < info.length; k++) {
            last = (last + 1) % info.length;
            if (last != myId && info[last] > 0) 
               return last;
        }
        return -1;
    }
}
