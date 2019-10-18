from os.path import join, exists, split
import glob
from matplotlib import pyplot as plt
import struct
import datetime
import numpy as np
from scipy import signal

import markup

# Copy files from Android /Internal shared storage/Android/data/com.example.positionmonitor/files/PositionMonitor
# to the folder specified here:
input_data_folder = "~/SleepData"
output_folder = "~/SleepData/plots"


class BreathingMonitorFile:
    def __init__(self, filename):
        value_count = 11

        self.filename = filename
        self.values = []
        self.values = [[] for _ in range(value_count)]
        self.start_unix_timestamp = None

        if filename.endswith(".dat"):
            with open(filename, "rb") as file_in:
                self.start_unix_timestamp = struct.unpack(">q", file_in.read(8))[0] / 1.0e3
                while True:
                    value_bytes = file_in.read(8 + (value_count - 1)*4)
                    if not value_bytes:
                        break
                    numbers = struct.unpack(">q{}f".format(value_count - 1), value_bytes)
                    for array, value in zip(self.values, numbers):
                        array.append(value)
        else:
            text = open(filename).read()
            lines = text.splitlines()
            for line in lines:
                numbers = [float(string) for string in line.split(",")]
                for array, value in zip(self.values, numbers):
                    array.append(value)
        self.values = [np.array(values, np.float64) for values in self.values]
        self.timestamps, self.positions, self.angles, self.azimuth, self.pitch, self.roll, self.thermistor, \
            self.spo2, self.pulse_rate, self.perfusion_index, self.mean_spo2_wave = self.values
        self.elapsed = self.timestamps / 1.0e9 - self.timestamps[0] / 1.0e9
        self.signal = self.positions
        self.thermistor = (self.thermistor - self.thermistor.mean()) * 0.02

        order = 3
        b, a = signal.butter(order, 0.1, 'low')
        zi = signal.lfilter_zi(b, a)
        self.low_pass, _ = signal.lfilter(b, a, self.signal, zi=zi * self.signal[0])
        b, a = signal.butter(order, 0.3, 'high')
        zi = signal.lfilter_zi(b, a)
        self.high_pass, _ = signal.lfilter(b, a, self.signal, zi=zi * self.signal[0])

        b, a = signal.butter(order, 0.15, 'low')
        zi = signal.lfilter_zi(b, a)
        self.thermistor_low_pass, _ = signal.lfilter(b, a, self.thermistor, zi=zi * self.thermistor[0])


def process_files():
    files = sorted(glob.glob(join(input_data_folder, "*")))
    filename = files[-1]
    data = BreathingMonitorFile(filename)

    report_folder = join(output_folder, split(filename)[1])
    report_filename = join(report_folder, split(filename)[1] + ".html")
    resource_folder = join(report_folder, "resources")
    report = markup.HtmlDocument(report_filename, resource_folder)

    fig_size = (18, 3)
    plt.figure(figsize=fig_size)
    plt.plot(data.elapsed[:-1], np.diff(data.elapsed))
    report.labels("Elapsed Time (s)", "Diff (s)", "Time Between Sensor Readings")
    plt.tight_layout()
    report.add_figure()

    max_data_points = 1000
    rows = []
    signal = -data.positions

    def region(values):
        return values[point_idx: point_idx + max_data_points]

    def set_non_zero_ylim(values):
        non_zero = values[values != 0]
        if len(non_zero) == 0:
            limits = [0, 1]
        else:
            limits = [non_zero.min(), non_zero.max()]
            margin = max(0.1, limits[1] - limits[0]) * 0.05
            limits = [limits[0] - margin, limits[1] + margin]
        plt.gca().set_ylim(limits)

    for point_idx in range(0, len(signal), max_data_points):
        timestamp = data.start_unix_timestamp + data.elapsed[point_idx]
        start_time = datetime.datetime.fromtimestamp(timestamp)
        x = region(data.elapsed)
        y = region(signal)
        roll = region(data.roll) * 180/np.pi
        pitch = region(data.pitch) * 180/np.pi
        thermistor_low_pass = region(data.thermistor_low_pass)
        spo2 = region(data.spo2)
        pulse_rate = region(data.pulse_rate)
        perfusion_index = region(data.perfusion_index)
        mean_spo2_wave = region(data.mean_spo2_wave)

        plt.figure(figsize=(fig_size[0], fig_size[1] * 3))
        plt.subplot(3, 1, 1)
        plt.plot(x, y)
        plt.plot(x, thermistor_low_pass)
        plt.legend(["Stomach", "Airflow"])
        report.labels("Elapsed (s)", "Acceleration Measure", start_time.isoformat().replace("T", " "))
        plt.xlim([x[0], x[-1]])
        plt.ylim([-0.2, 0.3])
        ax2 = plt.twinx(plt.gca())
        ax2.set_ylabel("Pitch (Green), Roll (Orange)")
        ax2.plot(x, pitch, color="g")
        ax2.plot(x, roll, color="#e6a800")
        ax2.set_ylim([-180, 180])

        plt.subplot(3, 1, 2)
        plt.plot(x, pulse_rate)
        set_non_zero_ylim(pulse_rate)
        plt.ylabel("Pulse Rate (BPM) (blue)")
        ax2 = plt.twinx(plt.gca())
        ax2.plot(x, spo2, color="g")
        set_non_zero_ylim(spo2)
        ax2.set_ylabel("SpO2 (green)")

        plt.subplot(3, 1, 3)
        plt.plot(x, perfusion_index)
        set_non_zero_ylim(perfusion_index)
        plt.ylabel("Perfusion Index (blue)")
        ax2 = plt.twinx(plt.gca())
        ax2.plot(x, mean_spo2_wave, color="g")
        set_non_zero_ylim(mean_spo2_wave)
        ax2.set_ylabel("Mean SpO2 Wave (green)")

        plt.tight_layout()

        rows.append([report.get_image()])
    report.table(rows)
    report.save()

    print("{} points, min={}, max={}".format(len(data.positions), min(data.positions), max(data.positions)))

    show_means = 0
    if show_means:
        plt.subplot(3, 1, 1)
        plt.plot(data.signal)
        plt.subplot(3, 1, 2)
        plt.plot(data.mean_pos_x)
        plt.plot(data.mean_pos_y)
        plt.plot(data.mean_pos_z)
        plt.subplot(3, 1, 3)
        plt.plot(data.mean_diff_x)
        plt.plot(data.mean_diff_y)
        plt.plot(data.mean_diff_z)
        plt.show()

    show_filtered = 0
    if show_filtered:
        plt.subplot(3, 1, 1)
        plt.plot(data.signal)
        plt.subplot(3, 1, 2)
        plt.plot(data.low_pass)
        plt.subplot(3, 1, 3)
        plt.plot(data.high_pass)
        plt.show()

    show_filtered_with_time = 0
    if show_filtered_with_time:
        plt.subplot(3, 1, 1)
        plt.plot(data.elapsed, data.signal)
        plt.subplot(3, 1, 2)
        plt.plot(data.elapsed, data.low_pass)
        plt.subplot(3, 1, 3)
        plt.plot(data.elapsed, data.high_pass)
        plt.show()

    show_position_angles = 0
    if show_position_angles:
        plt.subplot(3, 1, 1)
        plt.plot(data.positions)
        plt.subplot(3, 1, 2)
        plt.plot(data.angles)
        plt.subplot(3, 1, 3)
        plt.plot(data.combined)
        plt.show()

    show_angles = 0
    if show_angles:
        plt.subplot(3, 1, 1)
        plt.plot(data.azimuth)
        plt.subplot(3, 1, 2)
        plt.plot(data.pitch)
        plt.subplot(3, 1, 3)
        plt.plot(data.roll)
        plt.show()


if __name__ == "__main__":
    process_files()
