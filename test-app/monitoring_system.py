#!/usr/bin/env python3
"""
Live Monitoring System - Debugging Test Script
Simulates sensor data, alerts, and statistics that update in real-time
Perfect for testing debuggers and REPL functionality
"""

import time
import random
import json
import threading
from datetime import datetime
from collections import deque
from dataclasses import dataclass, asdict
from typing import List, Dict, Any
import math

@dataclass
class SensorReading:
    """Individual sensor reading with metadata"""
    sensor_id: str
    timestamp: str
    value: float
    unit: str
    status: str  # 'normal', 'warning', 'critical'

    def to_dict(self):
        return asdict(self)

class MonitoringSystem:
    """Main monitoring system with multiple sensors and analytics"""

    def __init__(self):
        self.sensors = {
            'temp_01': {'name': 'Temperature', 'unit': '°C', 'min': 15, 'max': 35, 'normal_range': (18, 28)},
            'press_01': {'name': 'Pressure', 'unit': 'bar', 'min': 0.8, 'max': 1.5, 'normal_range': (0.95, 1.2)},
            'flow_01': {'name': 'Flow Rate', 'unit': 'L/min', 'min': 0, 'max': 100, 'normal_range': (20, 80)},
            'cpu_01': {'name': 'CPU Usage', 'unit': '%', 'min': 0, 'max': 100, 'normal_range': (0, 75)},
            'mem_01': {'name': 'Memory', 'unit': 'GB', 'min': 0, 'max': 32, 'normal_range': (0, 24)}
        }

        # Historical data (last 100 readings per sensor)
        self.history = {sensor_id: deque(maxlen=100) for sensor_id in self.sensors}

        # Current readings
        self.current_readings = {}

        # Alert system
        self.alerts = deque(maxlen=50)
        self.alert_count = {'normal': 0, 'warning': 0, 'critical': 0}

        # Statistics
        self.statistics = {
            'total_readings': 0,
            'session_start': datetime.now().isoformat(),
            'uptime_seconds': 0,
            'anomaly_rate': 0.0
        }

        # System state
        self.running = False
        self.update_thread = None
        self.cycle_count = 0

        # Performance metrics
        self.performance = {
            'avg_processing_time': 0.0,
            'readings_per_second': 0.0,
            'memory_usage': 0.0
        }

        # Simulation parameters
        self.anomaly_chance = 0.15  # 15% chance of anomaly
        self.trend_direction = {sensor: random.choice([-1, 0, 1]) for sensor in self.sensors}

    def generate_reading(self, sensor_id: str) -> SensorReading:
        """Generate a single sensor reading with realistic patterns"""
        sensor_info = self.sensors[sensor_id]

        # Base value with trend
        if sensor_id in self.current_readings:
            # Smooth transition from last value
            last_value = self.current_readings[sensor_id].value
            trend = self.trend_direction[sensor_id] * 0.5
            noise = random.gauss(0, 0.3)
            value = last_value + trend + noise
        else:
            # Initial random value
            value = random.uniform(sensor_info['min'], sensor_info['max'])

        # Add periodic pattern for some sensors
        if sensor_id == 'cpu_01':
            # CPU has spikes
            if random.random() < 0.1:  # 10% chance of spike
                value = random.uniform(70, 95)
            else:
                # Sinusoidal base load
                value = 30 + 20 * math.sin(self.cycle_count / 10) + random.gauss(0, 5)
        elif sensor_id == 'temp_01':
            # Temperature has daily cycle
            hour = datetime.now().hour
            daily_factor = math.sin((hour - 6) * math.pi / 12)  # Peak at noon
            value = 22 + 3 * daily_factor + random.gauss(0, 0.5)

        # Clamp to sensor limits
        value = max(sensor_info['min'], min(sensor_info['max'], value))

        # Determine status
        normal_min, normal_max = sensor_info['normal_range']
        if value < normal_min * 0.9 or value > normal_max * 1.1:
            status = 'critical'
        elif value < normal_min or value > normal_max:
            status = 'warning'
        else:
            status = 'normal'

        # Random anomalies
        if random.random() < self.anomaly_chance:
            if random.random() < 0.3:  # 30% of anomalies are critical
                status = 'critical'
                # Push value out of range
                if random.random() < 0.5:
                    value = sensor_info['max'] * random.uniform(0.95, 1.05)
                else:
                    value = sensor_info['min'] * random.uniform(0.9, 1.1)
            else:
                status = 'warning'

        reading = SensorReading(
            sensor_id=sensor_id,
            timestamp=datetime.now().isoformat(),
            value=round(value, 2),
            unit=sensor_info['unit'],
            status=status
        )

        return reading

    def process_reading(self, reading: SensorReading):
        """Process a reading: update history, check alerts, update stats"""
        # Add to history
        self.history[reading.sensor_id].append(reading)

        # Update current
        self.current_readings[reading.sensor_id] = reading

        # Check for alerts
        if reading.status != 'normal':
            alert = {
                'timestamp': reading.timestamp,
                'sensor': reading.sensor_id,
                'sensor_name': self.sensors[reading.sensor_id]['name'],
                'value': reading.value,
                'unit': reading.unit,
                'status': reading.status,
                'message': f"{reading.status.upper()}: {self.sensors[reading.sensor_id]['name']} at {reading.value}{reading.unit}"
            }
            self.alerts.append(alert)
            self.alert_count[reading.status] += 1

        # Update statistics
        self.statistics['total_readings'] += 1
        self.alert_count[reading.status] += 1

    def calculate_analytics(self) -> Dict[str, Any]:
        """Calculate real-time analytics from sensor data"""
        analytics = {}

        for sensor_id, readings in self.history.items():
            if len(readings) > 0:
                values = [r.value for r in readings]
                analytics[sensor_id] = {
                    'current': self.current_readings.get(sensor_id).value if sensor_id in self.current_readings else None,
                    'avg': round(sum(values) / len(values), 2),
                    'min': round(min(values), 2),
                    'max': round(max(values), 2),
                    'trend': 'stable',  # Will calculate actual trend
                    'readings_count': len(values)
                }

                # Calculate trend
                if len(values) > 10:
                    recent = values[-10:]
                    older = values[-20:-10] if len(values) > 20 else values[:10]
                    recent_avg = sum(recent) / len(recent)
                    older_avg = sum(older) / len(older)

                    if recent_avg > older_avg * 1.05:
                        analytics[sensor_id]['trend'] = 'increasing'
                    elif recent_avg < older_avg * 0.95:
                        analytics[sensor_id]['trend'] = 'decreasing'

        return analytics

    def update_cycle(self):
        """Main update cycle - generates new readings"""
        start_time = time.time()

        # Generate readings for all sensors
        for sensor_id in self.sensors:
            reading = self.generate_reading(sensor_id)
            self.process_reading(reading)

        # Update cycle count
        self.cycle_count += 1

        # Randomly change trends
        if self.cycle_count % 20 == 0:  # Every 20 cycles
            for sensor in self.sensors:
                self.trend_direction[sensor] = random.choice([-1, 0, 1])

        # Update performance metrics
        processing_time = time.time() - start_time
        self.performance['avg_processing_time'] = round(
            (self.performance['avg_processing_time'] * 0.9 + processing_time * 0.1), 4
        )
        self.performance['readings_per_second'] = len(self.sensors) / processing_time if processing_time > 0 else 0

        # Update uptime
        start = datetime.fromisoformat(self.statistics['session_start'])
        self.statistics['uptime_seconds'] = int((datetime.now() - start).total_seconds())

        # Calculate anomaly rate
        total_alerts = sum(self.alert_count.values())
        if self.statistics['total_readings'] > 0:
            self.statistics['anomaly_rate'] = round(
                (total_alerts - self.alert_count['normal']) / self.statistics['total_readings'], 3
            )

    def run_monitoring(self):
        """Background thread for continuous monitoring"""
        while self.running:
            try:
                self.update_cycle()
                time.sleep(1)  # Update every second
            except Exception as e:
                print(f"Error in monitoring cycle: {e}")
                # Create error alert
                self.alerts.append({
                    'timestamp': datetime.now().isoformat(),
                    'sensor': 'system',
                    'status': 'critical',
                    'message': f"System error: {str(e)}"
                })

    def start(self):
        """Start the monitoring system"""
        if not self.running:
            self.running = True
            self.update_thread = threading.Thread(target=self.run_monitoring, daemon=True)
            self.update_thread.start()
            print("🚀 Monitoring system started")

    def stop(self):
        """Stop the monitoring system"""
        if self.running:
            self.running = False
            if self.update_thread:
                self.update_thread.join(timeout=2)
            print("🛑 Monitoring system stopped")

    def get_status(self) -> Dict[str, Any]:
        """Get complete system status"""
        return {
            'running': self.running,
            'cycle_count': self.cycle_count,
            'current_readings': {k: v.to_dict() for k, v in self.current_readings.items()},
            'statistics': self.statistics,
            'alert_count': self.alert_count,
            'recent_alerts': list(self.alerts)[-10:],  # Last 10 alerts
            'analytics': self.calculate_analytics(),
            'performance': self.performance
        }

    def get_sensor_history(self, sensor_id: str, limit: int = 50) -> List[Dict]:
        """Get historical data for a specific sensor"""
        if sensor_id in self.history:
            readings = list(self.history[sensor_id])[-limit:]
            return [r.to_dict() for r in readings]
        return []

    def trigger_anomaly(self, sensor_id: str = None):
        """Manually trigger an anomaly for testing"""
        if sensor_id is None:
            sensor_id = random.choice(list(self.sensors.keys()))

        sensor_info = self.sensors[sensor_id]
        # Create critical reading
        value = sensor_info['max'] * 1.2 if random.random() < 0.5 else sensor_info['min'] * 0.8

        reading = SensorReading(
            sensor_id=sensor_id,
            timestamp=datetime.now().isoformat(),
            value=round(value, 2),
            unit=sensor_info['unit'],
            status='critical'
        )

        self.process_reading(reading)
        print(f"⚠️ Anomaly triggered for {sensor_info['name']}: {reading.value}{reading.unit}")
        return reading.to_dict()

    def export_data(self, filename: str = None):
        """Export current state to JSON file"""
        if filename is None:
            filename = f"monitoring_dump_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"

        data = {
            'export_time': datetime.now().isoformat(),
            'status': self.get_status(),
            'full_history': {
                sensor_id: [r.to_dict() for r in readings]
                for sensor_id, readings in self.history.items()
            }
        }

        with open(filename, 'w') as f:
            json.dump(data, f, indent=2)

        print(f"📁 Data exported to {filename}")
        return filename


def main():
    """Main execution with interactive debugging points"""
    print("=" * 60)
    print("MONITORING SYSTEM - Debug Test Application")
    print("=" * 60)

    # Create system instance
    system = MonitoringSystem()

    # Start monitoring
    system.start()

    # Interactive loop
    print("\nCommands:")
    print("  s - Show current status")
    print("  a - Show analytics")
    print("  h <sensor_id> - Show sensor history")
    print("  t - Trigger random anomaly")
    print("  e - Export data to file")
    print("  p - Pause/Resume monitoring")
    print("  q - Quit")
    print("\nSystem is running. Set breakpoints and debug as needed!")
    print("-" * 60)

    try:
        while True:
            # This is a good debugging point - system state changes every iteration
            time.sleep(0.1)  # Short sleep for responsive CLI

            # Check for user input (non-blocking would be better, but keeping it simple)
            if random.random() < 0.01:  # Simulate periodic status display
                status = system.get_status()
                print(f"\n[{datetime.now().strftime('%H:%M:%S')}] Cycle: {status['cycle_count']}, "
                      f"Readings: {status['statistics']['total_readings']}, "
                      f"Alerts: W={status['alert_count']['warning']}, C={status['alert_count']['critical']}")

            # Random anomalies for interesting debugging
            if random.random() < 0.005:  # 0.5% chance per cycle
                system.trigger_anomaly()

            # You can set breakpoints here to inspect:
            # - system.current_readings - latest sensor values
            # - system.alerts - recent alerts
            # - system.statistics - overall stats
            # - system.calculate_analytics() - real-time analytics

    except KeyboardInterrupt:
        print("\n\nShutting down...")
        system.stop()

        # Final status
        final_status = system.get_status()
        print(f"\nFinal Statistics:")
        print(f"  Total readings: {final_status['statistics']['total_readings']}")
        print(f"  Uptime: {final_status['statistics']['uptime_seconds']} seconds")
        print(f"  Anomaly rate: {final_status['statistics']['anomaly_rate']*100:.1f}%")
        print(f"  Alerts: {sum(final_status['alert_count'].values())}")

        # Optional: Export final data
        export = input("\nExport data before exit? (y/n): ")
        if export.lower() == 'y':
            system.export_data()

    print("\n✅ Monitoring system terminated")


if __name__ == "__main__":
    main()