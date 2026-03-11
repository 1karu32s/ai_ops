package org.example.service;

import java.util.*;
public class Main{
    public static void main(String[] args){
        Scanner sc = new Scanner(System.in);
        if(!sc.hasNextInt()) return;
        int T = sc.nextInt();
        while(T-- > 0){
            int n = sc.nextInt();
            long k = sc.nextLong();
            long[] a = new long[n];
            long[] b = new long[n];

            long sum = 0;
            long maxH = Long.MAX_VALUE;
            for(int i = 0; i < n; i++){
                a[i] = sc.nextLong();
                b[i] = sc.nextLong();
                sum += a[i] + b[i];
                maxH = Math.min(maxH, a[i] + b[i]);
            }
            long left = 0;
            long right = maxH;
            long H = 0;

            while(left <= right){
                long mid = left + (right - left) / 2;
                if(isvalid(mid, n, k, a, b)){
                    H = mid;
                    left = mid + 1;
                }else{
                    right = mid - 1;
                }
            }
            long res = sum - n*H;
            System.out.println(res);
        }

    }

    private static boolean isvalid(long h, int n, long k, long[] a, long[] b){
        long curL = Math.max(0, h - b[0]);
        long curR = Math.max(a[0], h);
        if(curL > curR) return false;
        for(int i = 1; i < n; i++){
            long L = Math.max(0, h - b[i]);
            long R = Math.min(a[i], h);

            curL = Math.max(L, curL - k);
            curR = Math.max(R, curR + k);
            if(curL > curR) return false;
        }
        return true;
    }
}