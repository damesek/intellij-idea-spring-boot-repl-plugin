#!/usr/bin/env python3
"""
Data Processor - Simpler debugging target
Processes data in batches with various transformations
Good for testing breakpoints and variable inspection
"""

import time
import random
import json
from datetime import datetime
from typing import List, Dict, Any

class DataProcessor:
    """Processes batches of data with transformations and filters"""

    def __init__(self):
        self.processed_count = 0
        self.error_count = 0
        self.batch_number = 0
        self.current_batch = []
        self.results = []
        self.processing_rules = {
            'multiply': 1.5,
            'threshold': 50,
            'round_digits': 2
        }
        self.state = 'idle'  # idle, processing, error, completed

    def generate_batch(self, size: int = 10) -> List[Dict]:
        """Generate a batch of test data"""
        batch = []
        for i in range(size):
            item = {
                'id': f"item_{self.batch_number}_{i}",
                'value': random.uniform(0, 100),
                'category': random.choice(['A', 'B', 'C']),
                'timestamp': time.time(),
                'priority': random.choice(['low', 'medium', 'high'])
            }
            batch.append(item)

        self.batch_number += 1
        return batch

    def process_item(self, item: Dict) -> Dict:
        """Process a single item with transformations"""
        result = item.copy()

        # Apply transformations
        result['original_value'] = result['value']
        result['value'] = result['value'] * self.processing_rules['multiply']

        # Apply threshold
        if result['value'] > self.processing_rules['threshold']:
            result['status'] = 'above_threshold'
            result['alert'] = True
        else:
            result['status'] = 'normal'
            result['alert'] = False

        # Round value
        result['value'] = round(result['value'], self.processing_rules['round_digits'])

        # Add processing metadata
        result['processed_at'] = time.time()
        result['batch_number'] = self.batch_number

        # Simulate occasional errors
        if random.random() < 0.05:  # 5% error rate
            raise ValueError(f"Processing error for item {item['id']}")

        return result

    def process_batch(self, batch: List[Dict]) -> Dict:
        """Process an entire batch"""
        self.state = 'processing'
        self.current_batch = batch
        results = []
        errors = []
        start_time = time.time()

        for item in batch:
            try:
                # Good place for breakpoint - inspect item before processing
                processed = self.process_item(item)
                results.append(processed)
                self.processed_count += 1

                # Simulate processing delay
                time.sleep(0.01)

            except Exception as e:
                self.error_count += 1
                errors.append({
                    'item_id': item['id'],
                    'error': str(e),
                    'timestamp': time.time()
                })

        processing_time = time.time() - start_time

        # Calculate batch statistics
        batch_stats = {
            'batch_number': self.batch_number,
            'total_items': len(batch),
            'successful': len(results),
            'failed': len(errors),
            'processing_time': round(processing_time, 3),
            'avg_value': round(sum(r['value'] for r in results) / len(results), 2) if results else 0,
            'alerts': sum(1 for r in results if r.get('alert', False))
        }

        self.results.extend(results)
        self.state = 'completed' if not errors else 'error'

        return {
            'results': results,
            'errors': errors,
            'statistics': batch_stats
        }

    def get_summary(self) -> Dict:
        """Get processing summary"""
        return {
            'total_processed': self.processed_count,
            'total_errors': self.error_count,
            'batches_processed': self.batch_number,
            'current_state': self.state,
            'success_rate': round((self.processed_count / (self.processed_count + self.error_count)) * 100, 1)
                           if (self.processed_count + self.error_count) > 0 else 0
        }


class StreamProcessor:
    """Continuous stream processing with buffer"""

    def __init__(self, buffer_size: int = 100):
        self.buffer = []
        self.buffer_size = buffer_size
        self.stream_position = 0
        self.checkpoints = {}
        self.metrics = {
            'items_received': 0,
            'items_processed': 0,
            'buffer_overflows': 0,
            'checkpoint_saves': 0
        }

    def receive_item(self, item: Any) -> bool:
        """Add item to processing buffer"""
        if len(self.buffer) >= self.buffer_size:
            self.metrics['buffer_overflows'] += 1
            return False

        self.buffer.append({
            'position': self.stream_position,
            'data': item,
            'received_at': time.time()
        })
        self.stream_position += 1
        self.metrics['items_received'] += 1
        return True

    def process_buffer(self) -> List[Dict]:
        """Process and clear buffer"""
        if not self.buffer:
            return []

        processed = []
        for buffered_item in self.buffer:
            # Transform the item
            result = {
                'position': buffered_item['position'],
                'original': buffered_item['data'],
                'transformed': str(buffered_item['data']).upper() if isinstance(buffered_item['data'], str) else buffered_item['data'] * 2,
                'processing_delay': time.time() - buffered_item['received_at']
            }
            processed.append(result)
            self.metrics['items_processed'] += 1

        # Clear buffer after processing
        self.buffer.clear()
        return processed

    def create_checkpoint(self, name: str) -> Dict:
        """Create a checkpoint of current state"""
        checkpoint = {
            'name': name,
            'position': self.stream_position,
            'timestamp': time.time(),
            'metrics': self.metrics.copy(),
            'buffer_size': len(self.buffer)
        }
        self.checkpoints[name] = checkpoint
        self.metrics['checkpoint_saves'] += 1
        return checkpoint

    def get_state(self) -> Dict:
        """Get current processor state"""
        return {
            'buffer_count': len(self.buffer),
            'buffer_usage': f"{(len(self.buffer) / self.buffer_size) * 100:.1f}%",
            'stream_position': self.stream_position,
            'metrics': self.metrics,
            'checkpoints': list(self.checkpoints.keys())
        }


def main():
    """Main execution demonstrating various debugging scenarios"""
    print("DATA PROCESSOR - Debug Test")
    print("=" * 40)

    # Scenario 1: Batch Processing
    print("\n1. BATCH PROCESSING")
    processor = DataProcessor()

    for i in range(3):
        print(f"\n  Processing batch {i+1}...")
        batch = processor.generate_batch(5)

        # BREAKPOINT HERE - inspect batch before processing
        result = processor.process_batch(batch)

        print(f"  ✓ Processed: {result['statistics']['successful']}/{result['statistics']['total_items']} items")
        if result['errors']:
            print(f"  ✗ Errors: {len(result['errors'])}")

        time.sleep(0.5)

    print(f"\n  Summary: {processor.get_summary()}")

    # Scenario 2: Stream Processing
    print("\n2. STREAM PROCESSING")
    stream = StreamProcessor(buffer_size=10)

    # Simulate incoming stream
    for i in range(25):
        data = f"message_{i}" if i % 2 == 0 else i * 10

        # BREAKPOINT HERE - watch buffer fill up
        if stream.receive_item(data):
            print(f"  → Received: {data} (buffer: {len(stream.buffer)}/{stream.buffer_size})")
        else:
            print(f"  ✗ Buffer overflow at position {i}")

        # Process buffer when it reaches 70% capacity
        if len(stream.buffer) >= stream.buffer_size * 0.7:
            print("  ⚡ Processing buffer...")
            processed = stream.process_buffer()
            print(f"  ✓ Processed {len(processed)} items")

            # Create checkpoint
            stream.create_checkpoint(f"checkpoint_{i}")

        time.sleep(0.1)

    # Process remaining items
    if stream.buffer:
        print("\n  Final buffer processing...")
        processed = stream.process_buffer()
        print(f"  ✓ Processed {len(processed)} remaining items")

    print(f"\n  Final state: {json.dumps(stream.get_state(), indent=2)}")

    # Scenario 3: Complex Object Manipulation
    print("\n3. COMPLEX DATA STRUCTURES")

    nested_data = {
        'level1': {
            'level2': {
                'values': [1, 2, 3, 4, 5],
                'metadata': {
                    'created': datetime.now().isoformat(),
                    'version': '1.0'
                }
            },
            'items': [
                {'id': i, 'value': random.random()}
                for i in range(5)
            ]
        },
        'calculations': {}
    }

    # BREAKPOINT HERE - inspect nested structure
    print("  Processing nested data...")

    # Perform calculations
    nested_data['calculations']['sum'] = sum(nested_data['level1']['level2']['values'])
    nested_data['calculations']['avg'] = nested_data['calculations']['sum'] / len(nested_data['level1']['level2']['values'])
    nested_data['calculations']['item_total'] = sum(item['value'] for item in nested_data['level1']['items'])

    print(f"  Calculations: {nested_data['calculations']}")

    # Scenario 4: Error Handling Chain
    print("\n4. ERROR HANDLING")

    def risky_operation(value):
        """Operation that might fail"""
        if value < 0:
            raise ValueError("Negative value not allowed")
        if value > 100:
            raise OverflowError("Value too large")
        if value == 42:
            raise RuntimeError("The answer to everything!")
        return value * 2

    test_values = [10, -5, 150, 42, 30]
    results = []

    for val in test_values:
        try:
            # BREAKPOINT HERE - step through error handling
            result = risky_operation(val)
            results.append({'input': val, 'output': result, 'status': 'success'})
            print(f"  ✓ {val} → {result}")
        except ValueError as e:
            results.append({'input': val, 'error': str(e), 'status': 'value_error'})
            print(f"  ✗ {val}: {e}")
        except OverflowError as e:
            results.append({'input': val, 'error': str(e), 'status': 'overflow_error'})
            print(f"  ✗ {val}: {e}")
        except RuntimeError as e:
            results.append({'input': val, 'error': str(e), 'status': 'runtime_error'})
            print(f"  ✗ {val}: {e}")

    print(f"\n  Results: {len([r for r in results if r['status'] == 'success'])}/{len(results)} successful")

    print("\n" + "=" * 40)
    print("Debugging session complete!")
    print("\nKey debugging points:")
    print("  • Batch processing - line 67")
    print("  • Stream buffer - line 165")
    print("  • Nested data - line 230")
    print("  • Error handling - line 247")


if __name__ == "__main__":
    main()