package dev.ntsv.cleantv;

import java.util.Calendar;

/**
 * Vietnamese lunisolar calendar (Âm lịch, UTC+7), without ICU. Uses Hồ Ngọc Đức's astronomical
 * algorithm: new moon and sun longitude computed for the Vietnamese time zone, so Tết and leap
 * months match the official Vietnamese calendar (which can differ from the Chinese one).
 */
final class LunarVN {

    private static final double TZ = 7.0;
    private static final String[] CAN = {
            "Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý"};
    private static final String[] CHI = {
            "Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi"};

    private LunarVN() {
    }

    /** e.g. "Âm lịch 14/9 · Bính Ngọ"; a leap month reads "14/9 nhuận". */
    static String today() {
        Calendar c = Calendar.getInstance();
        return format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    static String format(int year, int month, int day) {
        int[] l = toLunar(day, month, year);
        return "Âm lịch " + l[0] + "/" + l[1] + (l[3] == 1 ? " nhuận" : "") + " · " + canChi(l[2]);
    }

    /** Can Chi of a lunar year, e.g. 2026 gives "Bính Ngọ". */
    static String canChi(int lunarYear) {
        return CAN[((lunarYear + 6) % 10 + 10) % 10] + " " + CHI[((lunarYear + 8) % 12 + 12) % 12];
    }

    /** Solar date to lunar: {day, month, year, leap (1 or 0)}. */
    static int[] toLunar(int dd, int mm, int yy) {
        int dayNumber = jdFromDate(dd, mm, yy);
        int k = (int) ((dayNumber - 2415021.076998695) / 29.530588853);
        int monthStart = newMoonDay(k + 1);
        if (monthStart > dayNumber) monthStart = newMoonDay(k);
        int a11 = lunarMonth11(yy);
        int b11 = a11;
        int lunarYear;
        if (a11 >= monthStart) {
            lunarYear = yy;
            a11 = lunarMonth11(yy - 1);
        } else {
            lunarYear = yy + 1;
            b11 = lunarMonth11(yy + 1);
        }
        int lunarDay = dayNumber - monthStart + 1;
        int diff = (monthStart - a11) / 29;
        boolean leap = false;
        int lunarMonth = diff + 11;
        if (b11 - a11 > 365) {
            int leapDiff = leapMonthOffset(a11);
            if (diff >= leapDiff) {
                lunarMonth = diff + 10;
                if (diff == leapDiff) leap = true;
            }
        }
        if (lunarMonth > 12) lunarMonth -= 12;
        if (lunarMonth >= 11 && diff < 4) lunarYear -= 1;
        return new int[]{lunarDay, lunarMonth, lunarYear, leap ? 1 : 0};
    }

    private static int jdFromDate(int dd, int mm, int yy) {
        int a = (14 - mm) / 12;
        int y = yy + 4800 - a;
        int m = mm + 12 * a - 3;
        int jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045;
        if (jd < 2299161) jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - 32083;
        return jd;
    }

    /** Julian day number (local date) of the k-th new moon since 1900-01-01. */
    private static int newMoonDay(int k) {
        double t = k / 1236.85;
        double t2 = t * t, t3 = t2 * t;
        double dr = Math.PI / 180;
        double jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3;
        jd1 += 0.00033 * Math.sin((166.56 + 132.87 * t - 0.009173 * t2) * dr);
        double m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3;
        double mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3;
        double f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3;
        double c1 = (0.1734 - 0.000393 * t) * Math.sin(m * dr) + 0.0021 * Math.sin(2 * dr * m);
        c1 -= 0.4068 * Math.sin(mpr * dr) - 0.0161 * Math.sin(dr * 2 * mpr);
        c1 -= 0.0004 * Math.sin(dr * 3 * mpr);
        c1 += 0.0104 * Math.sin(dr * 2 * f) - 0.0051 * Math.sin(dr * (m + mpr));
        c1 -= 0.0074 * Math.sin(dr * (m - mpr)) - 0.0004 * Math.sin(dr * (2 * f + m));
        c1 -= 0.0004 * Math.sin(dr * (2 * f - m)) + 0.0006 * Math.sin(dr * (2 * f + mpr));
        c1 += 0.0010 * Math.sin(dr * (2 * f - mpr)) + 0.0005 * Math.sin(dr * (2 * mpr + m));
        double deltaT = t < -11
                ? 0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3
                : -0.000278 + 0.000265 * t + 0.000262 * t2;
        return (int) (jd1 + c1 - deltaT + 0.5 + TZ / 24);
    }

    /** Sun's longitude at the given day, in 30 degree sectors 0..11. */
    private static int sunLongitude(double jdn) {
        double t = (jdn - 2451545.5 - TZ / 24) / 36525;
        double t2 = t * t;
        double dr = Math.PI / 180;
        double m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2;
        double l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2;
        double dl = (1.914600 - 0.004817 * t - 0.000014 * t2) * Math.sin(dr * m);
        dl += (0.019993 - 0.000101 * t) * Math.sin(dr * 2 * m) + 0.000290 * Math.sin(dr * 3 * m);
        double l = (l0 + dl) * dr;
        l -= Math.PI * 2 * (int) (l / (Math.PI * 2));
        return (int) (l / Math.PI * 6);
    }

    /** First day of the 11th lunar month (the one containing the winter solstice) of the year. */
    private static int lunarMonth11(int yy) {
        double off = jdFromDate(31, 12, yy) - 2415021.076998695;
        int k = (int) (off / 29.530588853);
        int nm = newMoonDay(k);
        if (sunLongitude(nm) >= 9) nm = newMoonDay(k - 1);
        return nm;
    }

    /** Offset (in months from month 11) of the leap month, for a 13-month lunar year. */
    private static int leapMonthOffset(int a11) {
        int k = (int) ((a11 - 2415021.076998695) / 29.530588853 + 0.5);
        int last;
        int i = 1;
        int arc = sunLongitude(newMoonDay(k + i));
        do {
            last = arc;
            i++;
            arc = sunLongitude(newMoonDay(k + i));
        } while (arc != last && i < 14);
        return i - 1;
    }
}
